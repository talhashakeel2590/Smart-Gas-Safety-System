#include <Arduino.h>
#include <WiFi.h>
#include <Firebase_ESP_Client.h>

#define BUZZER_PIN 25

#define WIFI_SSID "Testing"
#define WIFI_PASSWORD "12345"
#define DATABASE_URL "Your_database_url"
#define API_KEY "Your_key"
#define USER_EMAIL "tester@testing.com" // Your ESP32 user email
#define USER_PASSWORD "12345"

FirebaseData stream;
FirebaseAuth auth;
FirebaseConfig config;

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

  // Keep Wi-Fi awake for instant stream reception
  WiFi.setSleep(false);

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

  // Start the instant background stream
  if (!Firebase.RTDB.beginStream(&stream, "Device/emergency_shutoff")) {
    Serial.print("Stream setup error: ");
    Serial.println(stream.errorReason());
  } else {
    Serial.println("Stream active! Waiting for instant signals...");
    Firebase.RTDB.setStreamCallback(&stream, streamCallback, streamTimeoutCallback);
  }
}

void loop() {
  // Keeps the background stream alive without polling
  if (Firebase.ready()) {
    // Leave this empty
  }
}