package com.example.smartgassafety;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvGasPpm, tvSysStatusText, tvSysStatusDesc, tvFlameStatus, tvRiskScore;
    private DatabaseReference shutoffRef, gasRef, flameRef, historyRef;
    private CircularProgressIndicator gasGauge;
    private com.google.android.material.card.MaterialCardView cardStatus;
    private Button btnEmergencyShutoff, btnResetSystem, btnViewHistory;

    // Servo / Flame Control Slider
    // Servo angle convention (matches ESP32 firmware): 0 = valve SHUT, 90 = valve OPEN
    private static final String SERVO_PATH = "Device/servo_angle";
    private static final int SERVO_SHUT_ANGLE = 0;
    private static final int SERVO_OPEN_ANGLE = 90;
    private DatabaseReference servoRef;
    private Slider sliderServo;
    private TextView tvServoAngle, tvSliderLock;
    private boolean isHazardActive = false;   // locks the slider during emergency
    private boolean isDraggingSlider = false; // stops Firebase updates fighting the user's finger

    // AI Tracking Variables
    private int previousPpm = -1; // -1 = no previous reading yet
    private int latestPpm,latestRisk = 0;
    private boolean latestFlame = false;

    // Anti-Spam Flag for Historical Logging
    private boolean hasLoggedCurrentHazard = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        btnViewHistory = findViewById(R.id.btnViewHistory); // NEW
        sliderServo = findViewById(R.id.sliderServo);
        tvServoAngle = findViewById(R.id.tvServoAngle);
        tvSliderLock = findViewById(R.id.tvSliderLock);

        // 2. Initialize Firebase References
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        servoRef = database.getReference(SERVO_PATH);
        shutoffRef = database.getReference("Device/emergency_shutoff");
        gasRef = database.getReference("Device/gas_value");
        flameRef = database.getReference("Device/flame_status");
        historyRef = database.getReference("History");

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

                    if (!hasLoggedCurrentHazard) {
                        // History is now logged by the ESP32 (works offline too)
                        // Keep Firebase consistent with the physical knob (already shut by emergency)
                        servoRef.setValue(SERVO_SHUT_ANGLE);
                        hasLoggedCurrentHazard = true;
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

                    hasLoggedCurrentHazard = false;
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
                    calculateRiskScore(currentPpm);
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
                    latestFlame = isFlameOn;
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

        // 6. Handle Manual Emergency Trigger
        btnEmergencyShutoff.setOnClickListener(v -> shutoffRef.setValue(true));

        // 7. Handle System Reset
        btnResetSystem.setOnClickListener(v -> shutoffRef.setValue(false));

        // 8. Handle View History Button (NEW)
        btnViewHistory.setOnClickListener(v -> showHistoryBottomSheet());

        // 9. Flame Control Slider (NEW)
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

    // --- BOTTOM SHEET UI LOGIC ---
    private void showHistoryBottomSheet() {
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_history, null);
        bottomSheetDialog.setContentView(sheetView);

        LinearLayout llHistoryContainer = sheetView.findViewById(R.id.llHistoryContainer);
        ProgressBar pbHistoryLoading = sheetView.findViewById(R.id.pbHistoryLoading);
        TextView tvEmptyHistory = sheetView.findViewById(R.id.tvEmptyHistory);
        TextView tvCloseHistory = sheetView.findViewById(R.id.tvCloseHistory);

        tvCloseHistory.setOnClickListener(v -> bottomSheetDialog.dismiss());
        bottomSheetDialog.show();

        // Fetch last 20 logs from Firebase
        historyRef.orderByKey().limitToLast(20).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                pbHistoryLoading.setVisibility(View.GONE);
                llHistoryContainer.removeAllViews(); // Clear any existing views

                if (!snapshot.exists()) {
                    tvEmptyHistory.setVisibility(View.VISIBLE);
                    return;
                }

                // Put logs in a list and reverse it so the newest leak is at the top
                List<DataSnapshot> logList = new ArrayList<>();
                for (DataSnapshot logSnapshot : snapshot.getChildren()) {
                    logList.add(logSnapshot);
                }
                Collections.reverse(logList);

                // Build a UI Card for each log
                for (DataSnapshot logSnapshot : logList) {
                    String timestamp = logSnapshot.child("timestamp").getValue(String.class);
                    Integer peakPpm = logSnapshot.child("peak_ppm").getValue(Integer.class);
                    Integer riskScore = logSnapshot.child("risk_score").getValue(Integer.class);
                    Boolean flame = logSnapshot.child("flame_detected").getValue(Boolean.class);

                    // Inflate our custom card design
                    View cardView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_history_log, llHistoryContainer, false);

                    TextView tvLogTime = cardView.findViewById(R.id.tvLogTime);
                    TextView tvLogRisk = cardView.findViewById(R.id.tvLogRisk);
                    TextView tvLogPpm = cardView.findViewById(R.id.tvLogPpm);
                    TextView tvLogFlame = cardView.findViewById(R.id.tvLogFlame);

                    tvLogTime.setText(timestamp != null ? timestamp : "Unknown Time");
                    tvLogRisk.setText((riskScore != null ? riskScore : 0) + "%");
                    tvLogPpm.setText((peakPpm != null ? peakPpm : 0) + " PPM");

                    // Color code the risk text dynamically
                    if (riskScore != null) {
                        if (riskScore < 40) tvLogRisk.setTextColor(Color.parseColor("#34A853")); // Green
                        else if (riskScore < 75) tvLogRisk.setTextColor(Color.parseColor("#FBBC05")); // Yellow
                        else tvLogRisk.setTextColor(Color.parseColor("#EA4335")); // Red
                    }

                    if (flame != null && flame) {
                        tvLogFlame.setVisibility(View.VISIBLE);
                    }

                    llHistoryContainer.addView(cardView);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                pbHistoryLoading.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Failed to load history.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // --- AI RISK CALCULATOR ---
    private void calculateRiskScore(int currentPpm) {
        double proximityRisk = (currentPpm / 3500.0) * 100.0;
        if (proximityRisk > 100) proximityRisk = 100;

        int ppmJump = (previousPpm < 0) ? 0 : currentPpm - previousPpm;
        double velocityRisk = 0;
        if (ppmJump > 0) {
            velocityRisk = (ppmJump / 400.0) * 100.0;
            if (velocityRisk > 100) velocityRisk = 100;
        }

        double totalRisk = (proximityRisk * 0.7) + (velocityRisk * 0.3);
        latestRisk = (int) Math.round(totalRisk);

        tvRiskScore.setText(latestRisk + "%");

        if (latestRisk < 40) {
            tvRiskScore.setTextColor(Color.parseColor("#34A853"));
        } else if (latestRisk < 75) {
            tvRiskScore.setTextColor(Color.parseColor("#FBBC05"));
        } else {
            tvRiskScore.setTextColor(Color.parseColor("#EA4335"));
        }

        previousPpm = currentPpm;
    }
}