#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <Firebase_ESP_Client.h>
#include <ESP32Servo.h>
#include "secrets.h"  // WIFI_SSID, WIFI_PASSWORD, API_KEY, DATABASE_URL, USER_EMAIL, USER_PASSWORD

// Pins
#define BUZZER_PIN 25
#define GAS_SENSOR_PIN 32
#define FLAME_SENSOR_PIN 33
#define RESET_BUTTON_PIN 0
#define SERVO_PIN 26

// Servo convention: 0 = valve SHUT, 90 = valve OPEN
#define VALVE_SHUT_ANGLE 0
#define VALVE_OPEN_ANGLE 90
#define SERVO_MIN_US 500
#define SERVO_MAX_US 2400

// Active state for flame sensor (Change to HIGH if your module is active-high)
#define FLAME_DETECTED_STATE LOW

// Time zone for log timestamps (Pakistan = UTC+5)
#define GMT_OFFSET_SEC (5 * 3600)

// MQ-5 heater warm-up: no automatic gas shutoff for this long after power-up
#define WARMUP_MS 10000

// Gas level at which the sensors trigger a shutoff (also blocks releasing the knob)
#define GAS_HARD_LIMIT 2000

// Physical button: one press = reset (buzzer off, emergency cleared, knob released)
#define BUTTON_DEBOUNCE_MS 50

#define GAS_CLEAR_LIMIT 1500    // leak clears only when gas drops below this
#define MUTE_TIMEOUT_MS 60000   // buzzer snooze length
#define GAS_SPIKE_THRESHOLD 250 // A 250-count jump in one reading counts as a full "spike" (trigger + 100% velocity)

FirebaseData stream;       // Listens for emergency_shutoff
FirebaseData servoStream;  // Listens for servo_angle (app slider)
FirebaseData fbdo;         // Pushes live sensor data
FirebaseData logFbdo;      // Dedicated to uploading history events
FirebaseAuth auth;
FirebaseConfig config;
Servo gasValveServo;

// Valve position tracking (so Firebase always shows the real valve angle)
volatile int valveAngle = VALVE_SHUT_ANGLE;  // last angle the servo was told to go to
volatile bool servoAttached = true;          // false = knob released (free to turn by hand)
volatile int servoAngleSent = -1;            // last angle Firebase knows about (-1 = unknown)

// Per-user Firebase paths (set once the ESP32 is logged in)
String basePath;     // "Devices/<uid>/"
String historyPath;  // "History/<uid>"

// True during the first WARMUP_MS after boot (stays false afterwards, even if millis() wraps)
bool warmingUp() {
  static bool done = false;
  if (!done && millis() >= WARMUP_MS) done = true;
  return !done;
}

// The ONLY place that moves the valve, so valveAngle is always correct
void setValve(int angle) {
  if (!servoAttached) {  // take the knob back if it was released
    gasValveServo.attach(SERVO_PIN, SERVO_MIN_US, SERVO_MAX_US);
    servoAttached = true;
  }
  gasValveServo.write(angle);
  valveAngle = angle;
}

// Lets go of the knob so it can be turned by hand (the servo stops holding it)
void releaseValve() {
  gasValveServo.detach();
  servoAttached = false;
}

// Builds "Devices/<uid>/<leaf>" (uses += because String's + clashes with the Firebase library)
String devPath(const char *leaf) {
  String p = basePath;
  p += leaf;
  return p;
}

// State
unsigned long lastReadTime = 0;
unsigned long lastFlushTime = 0;
int previousGasValue = 0;
bool firstReading = true;
volatile bool emergencyActive = false;
volatile bool emergencyKnown = false;
bool fbBegun = false;
bool streamsReady = false;

// Leak flag used for app/admin notifications
bool leakDetected = false;  // true while the sensors report a leak
bool leakFlagSent = true;   // what Firebase holds (true = unknown at boot, forces a first sync)
int lastSentRisk = -1;      // last risk sent to Firebase (-1 = nothing sent yet)
bool pushSent = false;      // flag to track if push notification has been sent
volatile bool buzzerMuted = false;
volatile unsigned long muteStartTime = 0;

// Latest sensor snapshot (used by the event logger)
int lastGas = 0;
int lastRisk = 0;
bool lastFlame = false;

// Helpers used by the event logger (defined below)
void formatNow(char *buf, size_t n);
bool cloudOnline();
struct LogEvent;
bool pushEventToCloud(const LogEvent &e);

// ==== EVENT LOG BEGIN ====
// Small offline-safe queue: remembers up to MAX_EVENTS leak events (~30 bytes each)
// and uploads them to Firebase "History" once Wi-Fi is back.
#define MAX_EVENTS 5

struct LogEvent {
  char ts[20];  // "yyyy-MM-dd HH:mm:ss"
  int peak;
  int risk;
  bool flame;
};

LogEvent events[MAX_EVENTS];
int eventCount = 0;
bool eventOpen = false;  // newest event (last in queue) is still in progress

void startEvent(int gas, int risk, bool flame) {
  if (eventOpen) return;
  if (eventCount == MAX_EVENTS) {  // queue full -> drop the oldest
    for (int i = 1; i < MAX_EVENTS; i++) events[i - 1] = events[i];
    eventCount--;
  }
  LogEvent &e = events[eventCount++];
  formatNow(e.ts, sizeof(e.ts));
  e.peak = gas;
  e.risk = risk;
  e.flame = flame;
  eventOpen = true;
}

void updateEvent(int gas, int risk, bool flame) {
  if (!eventOpen) return;
  LogEvent &e = events[eventCount - 1];
  if (gas > e.peak) e.peak = gas;
  if (risk > e.risk) e.risk = risk;
  if (flame) e.flame = true;
}

void endEvent() {
  eventOpen = false;
}

// Opens/updates/closes the current event. Only leaks detected by the sensors are logged
// (a manual shutoff from the app is not a leak).
void updateEventState() {
  if (leakDetected) {
    if (!eventOpen) startEvent(lastGas, lastRisk, lastFlame);
    updateEvent(lastGas, lastRisk, lastFlame);
  } else {
    endEvent();
  }
}

// Uploads at most ONE finished event per call (keeps the main loop responsive)
void flushEvents() {
  int closed = eventOpen ? eventCount - 1 : eventCount;
  if (closed <= 0 || !cloudOnline()) return;
  if (!pushEventToCloud(events[0])) return;  // keep it and retry later
  for (int i = 1; i < eventCount; i++) events[i - 1] = events[i];
  eventCount--;
}
// ==== EVENT LOG END ====

void formatNow(char *buf, size_t n) {
  struct tm t;
  if (getLocalTime(&t, 100)) {
    strftime(buf, n, "%Y-%m-%d %H:%M:%S", &t);
  } else {
    strncpy(buf, "Time not synced", n - 1);
    buf[n - 1] = '\0';
  }
}

bool cloudOnline() {
  return streamsReady && fbBegun && WiFi.status() == WL_CONNECTED && Firebase.ready();
}

// Sends one OneSignal push to the audience described by targetingJson (filters, etc.)
void sendOneSignalPush(const String &targetingJson) {
  HTTPClient http;
  http.begin("https://onesignal.com/api/v1/notifications");
  http.addHeader("Content-Type", "application/json; charset=utf-8");
  String authHeader = "Basic ";
  authHeader.concat(ONESIGNAL_API_KEY);
  http.addHeader("Authorization", authHeader);

  String payload = "{\"app_id\":\"";
  payload += ONESIGNAL_APP_ID;
  payload += "\",\"headings\":{\"en\":\"⚠️ GAS LEAK DETECTED\"},\"contents\":{\"en\":\"Emergency shutoff activated. Check your gas supply.\"},";
  payload += targetingJson;
  payload += "}";

  int code = http.POST(payload);
  Serial.print(code > 0 ? "Push sent, code: " : "Push failed, code: ");
  Serial.println(code);
  http.end();
}

bool pushEventToCloud(const LogEvent &e) {
  FirebaseJson json;
  json.set("timestamp", e.ts);
  json.set("peak_ppm", e.peak);
  json.set("risk_score", e.risk);
  json.set("flame_detected", e.flame);
  bool ok = Firebase.RTDB.pushJSON(&logFbdo, historyPath.c_str(), &json);
  Serial.println(ok ? "History event uploaded." : "History upload failed, will retry.");
  return ok;
}

// After an emergency the valve is already shut (0), so "back to normal" only lets go of the knob.
// It stays held if the gas level is still unsafe (or the sensor is still warming up).
void releaseKnobIfSafe() {
  if (warmingUp()) {
    Serial.println("Knob stays held: sensor still warming up.");
    return;
  }
  Serial.println("Knob released - you can turn it by hand or use the slider.");
  releaseValve();
}

// Fires instantly when Firebase emergency_shutoff changes
void streamCallback(FirebaseStream data) {
  emergencyKnown = true;
  if (data.dataType() == "boolean") {
    bool isShutoff = data.boolData();
    bool wasEmergency = emergencyActive;

    if (isShutoff) {
      emergencyActive = true;
      Serial.println("STATUS: EMERGENCY SHUTOFF ACTIVE -> VALVE CLOSED");
      setValve(VALVE_SHUT_ANGLE);
    } else {
      if (!wasEmergency) return;  // already normal
      if (lastGas >= GAS_CLEAR_LIMIT || lastFlame || warmingUp()) {
        // SMART MUTE: buzzer off, valve stays shut, same leak
        Serial.println("App reset, but hazard still present! Muting buzzer only.");
        digitalWrite(BUZZER_PIN, LOW);
        buzzerMuted = true;
        muteStartTime = millis();
        emergencyActive = true;
        if (cloudOnline()) {  // keep the app red/locked
          Firebase.RTDB.setBoolAsync(&fbdo, devPath("emergency_shutoff").c_str(), true);
        }
      } else {
        // FULL RESET
        emergencyActive = false;
        leakDetected = false;
        buzzerMuted = false;
        Serial.println("STATUS: SYSTEM NORMAL -> BUZZER OFF");
        digitalWrite(BUZZER_PIN, LOW);
        releaseKnobIfSafe();
      }
    }
  }
}

// Fires when the app slider changes servo_angle
void servoStreamCallback(FirebaseStream data) {
  if (!emergencyKnown || emergencyActive || warmingUp()) return;  // never open the valve during a hazard or warm-up
  if (data.dataType() == "int") {
    int angle = constrain(data.intData(), VALVE_SHUT_ANGLE, VALVE_OPEN_ANGLE);
    if (!servoAttached && angle == valveAngle) return;  // knob is released: ignore the same old value
    Serial.print("SLIDER -> servo angle: ");
    Serial.println(angle);
    setValve(angle);
    servoAngleSent = angle;  // already in Firebase, no need to write it back
  }
}

void streamTimeoutCallback(bool timeout) {
  if (timeout) {
    Serial.println("Stream timed out, reconnecting...");
  }
}

// Connects to Firebase in the background (never blocks the safety logic)
void tryStartCloud() {
  if (streamsReady || WiFi.status() != WL_CONNECTED) return;

  if (!fbBegun) {  // Stage 1: Wi-Fi is up
    WiFi.setSleep(false);
    configTime(GMT_OFFSET_SEC, 0, "pool.ntp.org", "time.google.com");
    config.api_key = API_KEY;
    config.database_url = DATABASE_URL;
    auth.user.email = USER_EMAIL;
    auth.user.password = USER_PASSWORD;
    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
    fbBegun = true;
    Serial.println("Wi-Fi up -> starting Firebase...");
    return;
  }

  if (!Firebase.ready()) return;             // Stage 2: wait for login (non-blocking)
  if (auth.token.uid.length() == 0) return;  // wait until the user id is known

  // Every user's data lives under their own id
  String uid = auth.token.uid.c_str();
  basePath = "Devices/";
  basePath += uid;
  basePath += "/";
  historyPath = "History/";
  historyPath += uid;

  Firebase.RTDB.setInt(&fbdo, devPath("servo_angle").c_str(), valveAngle);
  servoAngleSent = valveAngle;
  if (Firebase.RTDB.beginStream(&stream, devPath("emergency_shutoff").c_str()))
    Firebase.RTDB.setStreamCallback(&stream, streamCallback, streamTimeoutCallback);
  if (Firebase.RTDB.beginStream(&servoStream, devPath("servo_angle").c_str()))
    Firebase.RTDB.setStreamCallback(&servoStream, servoStreamCallback, streamTimeoutCallback);
  streamsReady = true;
  Serial.println("Cloud ready: streams active.");
}

// Reset (button): buzzer off, emergency cleared, knob released (same as the app's System Reset)
void resetSystem() {
  Serial.println("SYSTEM RESET via Physical Button!");
  if (!emergencyActive) return;

  if (lastGas >= GAS_CLEAR_LIMIT || lastFlame || warmingUp()) {
    Serial.println("Hazard still present! Muting buzzer for 60s. Valve remains closed.");
    digitalWrite(BUZZER_PIN, LOW);
    buzzerMuted = true;
    muteStartTime = millis();
  } else {
    digitalWrite(BUZZER_PIN, LOW);
    emergencyActive = false;
    leakDetected = false;
    buzzerMuted = false;
    releaseKnobIfSafe();
    if (cloudOnline()) {
      Firebase.RTDB.setBoolAsync(&fbdo, devPath("emergency_shutoff").c_str(), false);
    }
  }
}

// ==== BUTTON BEGIN ====
// One press = one reset (ignores contact bounce, and holding the button does not repeat it)
bool buttonWasPressed = false;
unsigned long buttonLastLow = 0;  // last time the pin was seen pressed

void handleButton(bool pinPressed, unsigned long now) {
  if (pinPressed) buttonLastLow = now;
  // Counts as "still pressed" for a few ms after release, so bounce can't trigger a second reset
  bool held = pinPressed || (buttonLastLow != 0 && now - buttonLastLow < BUTTON_DEBOUNCE_MS);

  if (held && !buttonWasPressed) resetSystem();  // press just started
  buttonWasPressed = held;
}
// ==== BUTTON END ====

void setup() {
  Serial.begin(115200);

  // Servo setup
  ESP32PWM::allocateTimer(0);
  gasValveServo.setPeriodHertz(50);
  gasValveServo.attach(SERVO_PIN, SERVO_MIN_US, SERVO_MAX_US);
  setValve(VALVE_SHUT_ANGLE);  // Always boot with valve SHUT

  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(GAS_SENSOR_PIN, INPUT);
  pinMode(FLAME_SENSOR_PIN, INPUT);
  pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);

  // Startup beep
  digitalWrite(BUZZER_PIN, HIGH);
  delay(300);
  digitalWrite(BUZZER_PIN, LOW);

  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);  // no waiting: safety logic starts immediately
}

void loop() {
  tryStartCloud();

  // Sensor + edge logic run every second, independent of Wi-Fi/Firebase
  if (millis() - lastReadTime >= 1000) {
    lastReadTime = millis();

    int currentGas = analogRead(GAS_SENSOR_PIN);
    bool flameDetected = (digitalRead(FLAME_SENSOR_PIN) == FLAME_DETECTED_STATE);
    bool online = cloudOnline();

    Serial.print("Live Gas Value: ");
    Serial.println(currentGas);

    // Risk score (70% gas level + 30% spike speed)
    float proximity = min(currentGas / (float)GAS_HARD_LIMIT, 1.0f) * 100.0f;
    int jump = firstReading ? 0 : (currentGas - previousGasValue);
    float velocity = (jump > 0) ? min(jump / (float)GAS_SPIKE_THRESHOLD, 1.0f) * 100.0f : 0.0f;
    lastRisk = (int)round(proximity * 0.7f + velocity * 0.3f);
    lastGas = currentGas;
    lastFlame = flameDetected;

    if (warmingUp()) {
      lastRisk = 0;  // sensor is still heating up, don't show a false risk
      Serial.println("Sensor warming up - automatic shutoff paused");
    }

    // Telemetry (only when online)
    if (online) {
      Firebase.RTDB.setIntAsync(&fbdo, devPath("gas_value").c_str(), currentGas);
      Firebase.RTDB.setBoolAsync(&fbdo, devPath("flame_status").c_str(), flameDetected);

      // Risk is sent only when it changes
      if (lastRisk != lastSentRisk) {
        Firebase.RTDB.setIntAsync(&fbdo, devPath("risk_score").c_str(), lastRisk);
        lastSentRisk = lastRisk;
      }
    }
// Edge AI and both sensor logics
    bool hardGasLimit = (currentGas >= GAS_HARD_LIMIT);
    bool gasSpikeBurst = (jump >= GAS_SPIKE_THRESHOLD);
    firstReading = false;

        // 1. Trigger: once per leak (leakDetected stays true until the air clears)
    if (!warmingUp() && !leakDetected && (hardGasLimit || gasSpikeBurst)) {
      digitalWrite(BUZZER_PIN, HIGH);
      setValve(VALVE_SHUT_ANGLE);
      emergencyActive = true;
      leakDetected = true;
      buzzerMuted = false;
      Serial.print("HAZARD DETECTED (");
      Serial.print(hardGasLimit ? "gas limit" : "gas spike");
      if (flameDetected) Serial.print(", flame on");
      Serial.println(")! Buzzer ON, Valve CLOSED...");
    }
    // Cloud alerts: once per leak, as soon as we are online
        // Cloud alerts: once per leak. One POST to the owner's devices + every logged-in admin.
    if (leakDetected && online && !pushSent) {
      String tgt = "\"filters\":[{\"field\":\"tag\",\"key\":\"house\",\"relation\":\"=\",\"value\":\"";
      tgt += auth.token.uid.c_str();
      tgt += "\"},{\"operator\":\"OR\"},{\"field\":\"tag\",\"key\":\"role\",\"relation\":\"=\",\"value\":\"admin\"}]";
      sendOneSignalPush(tgt);
      pushSent = true;
      Firebase.RTDB.setBoolAsync(&fbdo, devPath("emergency_shutoff").c_str(), true);
    }

    // 2. Snooze expiry: buzzer returns only if the hazard is still there
    if (leakDetected && buzzerMuted && millis() - muteStartTime >= MUTE_TIMEOUT_MS) {
      if (currentGas >= GAS_CLEAR_LIMIT || flameDetected) {
        Serial.println("Mute expired! Hazard still active. Buzzer ON.");
        digitalWrite(BUZZER_PIN, HIGH);
        buzzerMuted = false;
      }
    }

    // 3. Auto-clear (hysteresis): only for sensor leaks, never for a manual shutoff
    if (leakDetected && currentGas < GAS_CLEAR_LIMIT && !flameDetected && !gasSpikeBurst) {
      Serial.println("Air cleared! Auto-returning to normal state.");
      emergencyActive = false;
      leakDetected = false;
      buzzerMuted = false;
      digitalWrite(BUZZER_PIN, LOW);
      releaseKnobIfSafe();
      if (online) {
        Firebase.RTDB.setBoolAsync(&fbdo, devPath("emergency_shutoff").c_str(), false);
      }
    }
    previousGasValue = currentGas;
  }

  // A reset (from the app or the button) clears the leak
  if (!emergencyActive) {
    leakDetected = false;
    pushSent = false;  // next leak can notify again
  }

  // Track the current leak event (start / peak / end)
  updateEventState();

  // Keep the leak flag in Firebase in sync (the app and admin send notifications from it)
  if (cloudOnline() && leakDetected != leakFlagSent) {
    Firebase.RTDB.setBoolAsync(&fbdo, devPath("leak_detected").c_str(), leakDetected);
    leakFlagSent = leakDetected;
  }
  // Keep the valve angle in Firebase in sync (covers opening/closing while Wi-Fi was down)
  if (cloudOnline() && valveAngle != servoAngleSent) {
    Firebase.RTDB.setIntAsync(&fbdo, devPath("servo_angle").c_str(), valveAngle);
    servoAngleSent = valveAngle;
  }
  // Upload any finished events (one every 3 s while online)
  if (millis() - lastFlushTime >= 3000) {
    lastFlushTime = millis();
    flushEvents();
  }
// Button Reset 
  handleButton(digitalRead(RESET_BUTTON_PIN) == LOW, millis());
}