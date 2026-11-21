#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <Firebase_ESP_Client.h>
#include <ESP32Servo.h>
#include "secrets.h"  // WIFI_SSID, WIFI_PASSWORD, API_KEY, DATABASE_URL, USER_EMAIL, USER_PASSWORD

// Pins (UPDATED to latest hardware setup)
#define BUZZER_PIN 32
#define GAS_SENSOR_PIN 35
#define FLAME_SENSOR_PIN 34
#define RESET_BUTTON_PIN 0
#define SERVO_PIN 27

// Servo convention: 0 = valve SHUT, 90 = valve OPEN
#define VALVE_SHUT_ANGLE 0
#define VALVE_OPEN_ANGLE 90
#define SERVO_MIN_US 500
#define SERVO_MAX_US 2400

// Active state for flame sensor
#define FLAME_DETECTED_STATE LOW

// Time zone for log timestamps (Pakistan = UTC+5)
#define GMT_OFFSET_SEC (5 * 3600)

// MQ-5 heater warm-up: 30 seconds to prevent cold-start false alarms
#define WARMUP_MS 30000

// Gas level at which the sensors trigger a shutoff
#define GAS_HARD_LIMIT 2000

#define GAS_CLEAR_LIMIT 1500    
#define MUTE_TIMEOUT_MS 60000   
#define GAS_SPIKE_THRESHOLD 250 

FirebaseData stream;       // Listens for emergency_shutoff
FirebaseData servoStream;  // Listens for servo_angle (app slider)
FirebaseData fbdo;         // Pushes live sensor data
FirebaseData logFbdo;      // Dedicated to uploading history events
FirebaseAuth auth;
FirebaseConfig config;
Servo gasValveServo;

// Valve position tracking
volatile int valveAngle = VALVE_SHUT_ANGLE;  
volatile bool servoAttached = true;          
volatile int servoAngleSent = -1;            

// Per-user Firebase paths
String basePath;     
String historyPath;  

// True during the first WARMUP_MS after boot
bool warmingUp() {
  static bool done = false;
  if (!done && millis() >= WARMUP_MS) done = true;
  return !done;
}

// The ONLY place that moves the valve
void setValve(int angle) {
  if (!servoAttached) {  
    gasValveServo.attach(SERVO_PIN, SERVO_MIN_US, SERVO_MAX_US);
    servoAttached = true;
  }
  gasValveServo.write(angle);
  valveAngle = angle;
}

void releaseValve() {
  gasValveServo.detach();
  servoAttached = false;
}

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

bool leakDetected = false;  
bool leakFlagSent = true;   
int lastSentRisk = -1;      
bool pushSent = false;      
volatile bool buzzerMuted = false;
volatile unsigned long muteStartTime = 0;

int lastGas = 0;
int lastRisk = 0;
bool lastFlame = false;
unsigned long lastButtonPress = 0; // Added for simplified debounce

// Helpers used by the event logger
void formatNow(char *buf, size_t n);
bool cloudOnline();
struct LogEvent;
bool pushEventToCloud(const LogEvent &e);

// ==== EVENT LOG BEGIN ====
#define MAX_EVENTS 5

struct LogEvent {
  char ts[20];  // "yyyy-MM-dd HH:mm:ss"
  int peak;
  int risk;
  bool flame;
};

LogEvent events[MAX_EVENTS];
int eventCount = 0;
bool eventOpen = false;

void startEvent(int gas, int risk, bool flame) {
  if (eventOpen) return;
  if (eventCount == MAX_EVENTS) { 
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

void updateEventState() {
  if (leakDetected) {
    if (!eventOpen) startEvent(lastGas, lastRisk, lastFlame);
    updateEvent(lastGas, lastRisk, lastFlame);
  } else {
    endEvent();
  }
}

void flushEvents() {
  int closed = eventOpen ? eventCount - 1 : eventCount;
  if (closed <= 0 || !cloudOnline()) return;
  if (!pushEventToCloud(events[0])) return; 
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

void releaseKnobIfSafe() {
  if (warmingUp()) {
    Serial.println("Knob stays held: sensor still warming up.");
    return;
  }
  Serial.println("Knob released - you can turn it by hand or use the slider.");
  releaseValve();
}

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
      if (!wasEmergency) return;  
      if (lastGas >= GAS_CLEAR_LIMIT || lastFlame || warmingUp()) {
        Serial.println("App reset, but hazard still present! Muting buzzer only.");
        digitalWrite(BUZZER_PIN, LOW);
        buzzerMuted = true;
        muteStartTime = millis();
        emergencyActive = true;
        if (cloudOnline()) {  
          Firebase.RTDB.setBoolAsync(&fbdo, devPath("emergency_shutoff").c_str(), true);
        }
      } else {
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

void servoStreamCallback(FirebaseStream data) {
  if (!emergencyKnown || emergencyActive || warmingUp()) return;  
  if (data.dataType() == "int") {
    int angle = constrain(data.intData(), VALVE_SHUT_ANGLE, VALVE_OPEN_ANGLE);
    if (!servoAttached && angle == valveAngle) return;  
    Serial.print("SLIDER -> servo angle: ");
    Serial.println(angle);
    setValve(angle);
    servoAngleSent = angle;  
  }
}

void streamTimeoutCallback(bool timeout) {
  if (timeout) {
    Serial.println("Stream timed out, reconnecting...");
  }
}

void tryStartCloud() {
  // FIX: If Wi-Fi drops, flag streams as dead so they restart when Wi-Fi comes back
  if (WiFi.status() != WL_CONNECTED) {
    streamsReady = false; 
    return;
  }
  
  if (streamsReady) return;

  if (!fbBegun) {  
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

  if (!Firebase.ready()) return;             
  if (auth.token.uid.length() == 0) return;  

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

void setup() {
  Serial.begin(115200);

  ESP32PWM::allocateTimer(0);
  gasValveServo.setPeriodHertz(50);
  gasValveServo.attach(SERVO_PIN, SERVO_MIN_US, SERVO_MAX_US);
  setValve(VALVE_SHUT_ANGLE);  

  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(GAS_SENSOR_PIN, INPUT);
  pinMode(FLAME_SENSOR_PIN, INPUT);
  pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);

  digitalWrite(BUZZER_PIN, HIGH);
  delay(300);
  digitalWrite(BUZZER_PIN, LOW);

  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);  
}

void loop() {
  tryStartCloud();

  if (millis() - lastReadTime >= 1000) {
    lastReadTime = millis();

    int currentGas = analogRead(GAS_SENSOR_PIN);
    bool flameDetected = (digitalRead(FLAME_SENSOR_PIN) == FLAME_DETECTED_STATE);
    bool online = cloudOnline();

    Serial.print("Live Gas Value: ");
    Serial.println(currentGas);

    // --- 1. TRIGGER LOGIC (Moved before math so 100% fires instantly) ---
    int jump = firstReading ? 0 : (currentGas - previousGasValue);
    bool hardGasLimit = (currentGas >= GAS_HARD_LIMIT);
    bool gasSpikeBurst = (jump >= GAS_SPIKE_THRESHOLD);
    
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
    firstReading = false;

    // --- 2. RISK & TELEMETRY LOGIC ---
    float proximity = min(currentGas / (float)GAS_HARD_LIMIT, 1.0f) * 100.0f;
    float velocity = (jump > 0) ? min(jump / (float)GAS_SPIKE_THRESHOLD, 1.0f) * 100.0f : 0.0f;
    lastRisk = (int)round(proximity * 0.7f + velocity * 0.3f);
    
    // Force 100% immediately if the trigger above just fired
    if (leakDetected) {
      lastRisk = 100;
    }
    
    lastGas = currentGas;
    lastFlame = flameDetected;

    if (warmingUp()) {
      lastRisk = 0;  
      Serial.println("Sensor warming up - automatic shutoff paused");
    }

    if (online) {
      Firebase.RTDB.setIntAsync(&fbdo, devPath("gas_value").c_str(), currentGas);
      Firebase.RTDB.setBoolAsync(&fbdo, devPath("flame_status").c_str(), flameDetected);

      if (lastRisk != lastSentRisk) {
        Firebase.RTDB.setIntAsync(&fbdo, devPath("risk_score").c_str(), lastRisk);
        lastSentRisk = lastRisk;
      }
    }

    // --- 3. PUSH ALERTS ---
    if (leakDetected && online && !pushSent) {
      String tgt = "\"filters\":[{\"field\":\"tag\",\"key\":\"house\",\"relation\":\"=\",\"value\":\"";
      tgt += auth.token.uid.c_str();
      tgt += "\"},{\"operator\":\"OR\"},{\"field\":\"tag\",\"key\":\"role\",\"relation\":\"=\",\"value\":\"admin\"}]";
      sendOneSignalPush(tgt);
      pushSent = true;
      Firebase.RTDB.setBoolAsync(&fbdo, devPath("emergency_shutoff").c_str(), true);
    }

    // --- 4. SNOOZE EXPIRY ---
    if (leakDetected && buzzerMuted && millis() - muteStartTime >= MUTE_TIMEOUT_MS) {
      if (currentGas >= GAS_CLEAR_LIMIT || flameDetected) {
        Serial.println("Mute expired! Hazard still active. Buzzer ON.");
        digitalWrite(BUZZER_PIN, HIGH);
        buzzerMuted = false;
      }
    }

    // *** AUTO-CLEAR BLOCK HAS BEEN COMPLETELY DELETED HERE ***

    previousGasValue = currentGas;
  }

  if (!emergencyActive) {
    leakDetected = false;
    pushSent = false;  
  }

  // Track the current leak event (start / peak / end)
  updateEventState();

  if (cloudOnline() && leakDetected != leakFlagSent) {
    Firebase.RTDB.setBoolAsync(&fbdo, devPath("leak_detected").c_str(), leakDetected);
    leakFlagSent = leakDetected;
  }
  if (cloudOnline() && valveAngle != servoAngleSent) {
    Firebase.RTDB.setIntAsync(&fbdo, devPath("servo_angle").c_str(), valveAngle);
    servoAngleSent = valveAngle;
  }
  
  // Upload any finished events (one every 3 s while online)
  if (millis() - lastFlushTime >= 3000) {
    lastFlushTime = millis();
    flushEvents();
  }

  // Simplified Button Debounce (1-second lockout prevents bounce spam)
  if (digitalRead(RESET_BUTTON_PIN) == LOW) {
    if (millis() - lastButtonPress > 1000) { 
      resetSystem();
      lastButtonPress = millis();
    }
  }
}
