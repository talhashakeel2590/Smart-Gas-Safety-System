#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>

// Sensor Pins
#define BUZZER_PIN 25
#define GAS_SENSOR_PIN 32
#define FLAME_SENSOR_PIN 33
#define RESET_BUTTON_PIN 0 // The built-in "BOOT" button on the ESP32 board

// Active state for flame sensor (Change to LOW if your module is active-low)
#define FLAME_DETECTED_STATE LOW

// Wi-Fi & Firebase Credentials
#define WIFI_SSID "Testing"
#define WIFI_PASSWORD "12345"
#define DATABASE_URL "Your_database_url"
#define API_KEY "Your_key"
#define USER_EMAIL "tester@testing.com"
#define USER_PASSWORD "12345"

FirebaseData stream; // Dedicated to listening for shutoff
FirebaseData fbdo;   // Dedicated to pushing sensor data
FirebaseAuth auth;
FirebaseConfig config;

// State Variables
unsigned long lastReadTime = 0;
int previousGasValue = 0;

// This callback fires INSTANTLY when Firebase changes
void streamCallback(FirebaseStream data) {
  if (data.dataType() == "boolean") {
    bool isShutoff = data.boolData();

    if (isShutoff) {
      Serial.println("STATUS: EMERGENCY SHUTOFF ACTIVE -> BUZZER ON");
      digitalWrite(BUZZER_PIN, HIGH);
    } else {
      Serial.println("STATUS: SYSTEM NORMAL -> BUZZER OFF");
      digitalWrite(BUZZER_PIN, LOW);
    }
  }
}

void streamTimeoutCallback(bool timeout) {
  if (timeout) {
    Serial.println("Stream timed out, reconnecting...");
  }
}

void setup() {
  Serial.begin(115200);

  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(GAS_SENSOR_PIN, INPUT);
  pinMode(FLAME_SENSOR_PIN, INPUT);
  pinMode(RESET_BUTTON_PIN, INPUT_PULLUP); // Enable built-in button

  // Startup Beep
  digitalWrite(BUZZER_PIN, HIGH);
  delay(300);
  digitalWrite(BUZZER_PIN, LOW);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connecting to Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    Serial.print(".");
    delay(500);
  }
  Serial.println("\nWi-Fi Connected!");

  WiFi.setSleep(false); // Keep awake for instant stream

  config.api_key = API_KEY;
  config.database_url = DATABASE_URL;
  auth.user.email = USER_EMAIL;
  auth.user.password = USER_PASSWORD;

  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);

  Serial.print("Authenticating with Firebase");
  while (!Firebase.ready()) {
    Serial.print(".");
    delay(500);
  }
  Serial.println("\nFirebase Authenticated successfully!");

  if (!Firebase.RTDB.beginStream(&stream, "Device/emergency_shutoff")) {
    Serial.print("Stream setup error: ");
    Serial.println(stream.errorReason());
  } else {
    Serial.println("Stream active! Waiting for instant signals...");
    Firebase.RTDB.setStreamCallback(&stream, streamCallback, streamTimeoutCallback);
  }
}

void loop() {
  // We use millis() instead of delay() to avoid blocking the instant Firebase stream
  if (Firebase.ready() && (millis() - lastReadTime >= 1000)) {
    lastReadTime = millis();

    int currentGas = analogRead(GAS_SENSOR_PIN);
    bool flameDetected = (digitalRead(FLAME_SENSOR_PIN) == FLAME_DETECTED_STATE);

    Serial.print("Live Gas Value (with WiFi on): ");
    Serial.println(currentGas);

    // Send Live Telemetry to Firebase (For Android UI and Cloud AI)
    Firebase.RTDB.setIntAsync(&fbdo, "Device/gas_value", currentGas);
    Firebase.RTDB.setBoolAsync(&fbdo, "Device/flame_status", flameDetected);

    // -------------------------------------------------------------
    // HYBRID EDGE AI & SENSOR LOGIC (Runs even if Android is offline)
    // -------------------------------------------------------------
    // Adjusted thresholds because ESP32 Wi-Fi shifts the baseline up!
    bool hardGasLimit = (currentGas >= 3500); 
    bool gasSpikeBurst = ((currentGas - previousGasValue) >= 400); 
    
    // Evaluate logic based on your criteria
    if (hardGasLimit || gasSpikeBurst || (flameDetected && currentGas >= 3000)) {
        // TRUE EDGE REFLEX: Turn on buzzer immediately, even if Wi-Fi is down!
        digitalWrite(BUZZER_PIN, HIGH);
        
        // Push 'true' to Firebase to sync the Android App (if online)
        Firebase.RTDB.setBoolAsync(&fbdo, "Device/emergency_shutoff", true);
        Serial.println("HAZARD DETECTED! Buzzer ON...");
    }

    previousGasValue = currentGas;
  }

  // -------------------------------------------------------------
  // PHYSICAL OFFLINE RESET (Press BOOT button on ESP32)
  // -------------------------------------------------------------
  if (digitalRead(RESET_BUTTON_PIN) == LOW) {
      Serial.println("SYSTEM RESET via Physical BOOT Button!");
      digitalWrite(BUZZER_PIN, LOW); // Turn off local buzzer instantly
      
      // Tell the Android app the system is safe again (if Wi-Fi is connected)
      if (Firebase.ready()) {
          Firebase.RTDB.setBoolAsync(&fbdo, "Device/emergency_shutoff", false);
      }
      delay(1000); // 1-second delay so it doesn't spam from a single press
  }
}