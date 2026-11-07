package com.example.smartgassafety;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private TextView tvGasPpm, tvSysStatusText, tvSysStatusDesc, tvFlameStatus, tvRiskScore;
    private DatabaseReference shutoffRef, gasRef, flameRef, riskRef, leakRef, historyRef;
    private CircularProgressIndicator gasGauge;
    private com.google.android.material.card.MaterialCardView cardStatus;
    private Button btnEmergencyShutoff, btnResetSystem, btnViewHistory;

    // Servo / Flame Control Slider
    // Servo angle convention (matches ESP32 firmware): 0 = valve SHUT, 90 = valve OPEN
    private static final int SERVO_SHUT_ANGLE = 0;
    private static final int SERVO_OPEN_ANGLE = 90;
    private DatabaseReference servoRef;
    private Slider sliderServo;
    private TextView tvServoAngle, tvSliderLock;
    private boolean isHazardActive = false;   // locks the slider during emergency
    private boolean isDraggingSlider = false; // stops Firebase updates fighting the user's finger

    private int latestPpm = 0;                        // shown in the leak notification
    private boolean hasHandledCurrentHazard = false;  // servo sync runs once per hazard
    private boolean hasNotifiedLeak = false;          // leak notification is sent once per leak

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Only a logged-in user can see this page
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        NotificationHelper.setup(this);

        // 1. Initialize UI Elements
        tvGasPpm = findViewById(R.id.tvGasPpm);
        tvSysStatusText = findViewById(R.id.tvSysStatusText);
        tvSysStatusDesc = findViewById(R.id.tvSysStatusDesc);
        tvFlameStatus = findViewById(R.id.tvFlameStatus);
        gasGauge = findViewById(R.id.gasGauge);
        cardStatus = findViewById(R.id.cardStatus);
        btnEmergencyShutoff = findViewById(R.id.btnEmergencyShutoff);
        btnResetSystem = findViewById(R.id.btnResetSystem);
        tvRiskScore = findViewById(R.id.tvRiskScore);
        btnViewHistory = findViewById(R.id.btnViewHistory);
        sliderServo = findViewById(R.id.sliderServo);
        tvServoAngle = findViewById(R.id.tvServoAngle);
        tvSliderLock = findViewById(R.id.tvSliderLock);

        // 2. Firebase references - every user has their own device and history node
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference deviceRef = database.getReference("Devices").child(user.getUid());
        shutoffRef = deviceRef.child("emergency_shutoff");
        gasRef = deviceRef.child("gas_value");
        flameRef = deviceRef.child("flame_status");
        riskRef = deviceRef.child("risk_score");
        leakRef = deviceRef.child("leak_detected");
        servoRef = deviceRef.child("servo_angle");
        historyRef = database.getReference("History").child(user.getUid());

        // 3. Monitor Emergency Shutoff State
        shutoffRef.addValueEventListener(new ValueEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean isHazard = snapshot.getValue(Boolean.class);

                if (isHazard != null && isHazard) {
                    tvSysStatusText.setText("HAZARD");
                    tvSysStatusText.setTextColor(getResources().getColor(R.color.accent_red));
                    tvSysStatusDesc.setText("⚠️ Emergency Shutoff Active");
                    gasGauge.setIndicatorColor(getResources().getColor(R.color.accent_red));
                    cardStatus.setStrokeColor(getResources().getColor(R.color.accent_red));

                    btnEmergencyShutoff.setVisibility(View.GONE);
                    btnResetSystem.setVisibility(View.VISIBLE);

                    // Lock the flame slider and show it at the shut position
                    isHazardActive = true;
                    isDraggingSlider = false;
                    sliderServo.setEnabled(false);
                    sliderServo.setValue(SERVO_SHUT_ANGLE);
                    tvServoAngle.setText(SERVO_SHUT_ANGLE + "°");
                    tvSliderLock.setVisibility(View.VISIBLE);

                    // Keep Firebase consistent with the physical knob (already shut), once per hazard
                    if (!hasHandledCurrentHazard) {
                        servoRef.setValue(SERVO_SHUT_ANGLE);
                        hasHandledCurrentHazard = true;
                    }
                } else {
                    tvSysStatusText.setText("NORMAL");
                    tvSysStatusText.setTextColor(getResources().getColor(R.color.accent_green));
                    tvSysStatusDesc.setText("✅ No hazards detected");
                    gasGauge.setIndicatorColor(getResources().getColor(R.color.accent_blue));
                    cardStatus.setStrokeColor(getResources().getColor(R.color.border_color));

                    btnEmergencyShutoff.setVisibility(View.VISIBLE);
                    btnResetSystem.setVisibility(View.GONE);

                    // Unlock the flame slider (it stays at the shut position until the user opens it)
                    isHazardActive = false;
                    sliderServo.setEnabled(true);
                    tvSliderLock.setVisibility(View.GONE);

                    hasHandledCurrentHazard = false;
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Shutoff listen failed", error.toException());
            }
        });

        // 4. Monitor Live Gas PPM
        gasRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer currentPpm = snapshot.getValue(Integer.class);
                if (currentPpm != null) {
                    latestPpm = currentPpm;
                    tvGasPpm.setText(String.valueOf(currentPpm));
                    gasGauge.setProgressCompat(currentPpm, true);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Gas listen failed", error.toException());
            }
        });

        // 5. Monitor Flame Status
        flameRef.addValueEventListener(new ValueEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean isFlameOn = snapshot.getValue(Boolean.class);
                if (isFlameOn != null) {
                    if (isFlameOn) {
                        tvFlameStatus.setText("FLAME ON 🔥");
                        tvFlameStatus.setTextColor(Color.parseColor("#EA4335"));
                    } else {
                        tvFlameStatus.setText("NO FLAME");
                        tvFlameStatus.setTextColor(Color.parseColor("#5F6368"));
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Flame listen failed", error.toException());
            }
        });

        // 6. Monitor the AI risk score (calculated on the ESP32)
        riskRef.addValueEventListener(new ValueEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer risk = snapshot.getValue(Integer.class);
                if (risk != null) {
                    tvRiskScore.setText(risk + "%");
                    tvRiskScore.setTextColor(RiskUtils.colorFor(risk));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Risk listen failed", error.toException());
            }
        });

        // 7. Send a notification when the ESP32 reports a leak (once per leak)
        leakRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean leak = snapshot.getValue(Boolean.class);
                if (leak != null && leak) {
                    if (!hasNotifiedLeak) {
                        NotificationHelper.sendLeakAlert(MainActivity.this, 1,
                                "Emergency shutoff activated. Gas level: " + latestPpm + " PPM",
                                MainActivity.class);
                        hasNotifiedLeak = true;
                    }
                } else {
                    hasNotifiedLeak = false;
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Leak listen failed", error.toException());
            }
        });

        // 8. Buttons
        btnEmergencyShutoff.setOnClickListener(v -> shutoffRef.setValue(true));
        btnResetSystem.setOnClickListener(v -> shutoffRef.setValue(false));
        btnViewHistory.setOnClickListener(v -> HistoryHelper.show(this, historyRef));

        // 9. Flame Control Slider
        setupServoSlider();
    }

    // --- SERVO SLIDER LOGIC ---
    @SuppressLint("SetTextI18n")
    private void setupServoSlider() {
        // Live label while dragging (UI only - nothing is sent to Firebase yet)
        sliderServo.addOnChangeListener((slider, value, fromUser) ->
                tvServoAngle.setText((int) value + "°"));

        // Send the angle once, when the finger is lifted (avoids flooding Firebase / servo jitter)
        sliderServo.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                isDraggingSlider = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                isDraggingSlider = false;
                if (isHazardActive) return; // never command the knob open during an emergency
                servoRef.setValue((int) slider.getValue());
            }
        });

        // Keep the slider in sync with Firebase (app restart, other devices, ESP updates)
        servoRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer angle = snapshot.getValue(Integer.class);
                if (angle == null || isDraggingSlider) return;

                int safeAngle = Math.max(SERVO_SHUT_ANGLE, Math.min(SERVO_OPEN_ANGLE, angle));
                sliderServo.setValue(safeAngle);
                tvServoAngle.setText(safeAngle + "°");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Servo listen failed", error.toException());
            }
        });
    }
}