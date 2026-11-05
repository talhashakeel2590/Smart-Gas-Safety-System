package com.example.smartgassafety;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private TextView tvGasPpm, tvSysStatusText, tvSysStatusDesc, tvFlameStatus, tvRiskScore;
    private DatabaseReference shutoffRef, gasRef, flameRef;
    private CircularProgressIndicator gasGauge;
    private com.google.android.material.card.MaterialCardView cardStatus;
    private Button btnEmergencyShutoff, btnResetSystem;
    private int previousPpm = 0;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI Elements
        tvGasPpm = findViewById(R.id.tvGasPpm);
        tvSysStatusText = findViewById(R.id.tvSysStatusText);
        tvSysStatusDesc = findViewById(R.id.tvSysStatusDesc);
        tvFlameStatus = findViewById(R.id.tvFlameStatus);
        gasGauge = findViewById(R.id.gasGauge);
        cardStatus = findViewById(R.id.cardStatus);
        btnEmergencyShutoff = findViewById(R.id.btnEmergencyShutoff);
        btnResetSystem = findViewById(R.id.btnResetSystem);
        tvRiskScore = findViewById(R.id.tvRiskScore);

        // Initialize Firebase References
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        shutoffRef = database.getReference("Device/emergency_shutoff");
        gasRef = database.getReference("Device/gas_value");
        flameRef = database.getReference("Device/flame_status");

        // Monitor Emergency Shutoff State
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
                } else {
                    tvSysStatusText.setText("NORMAL");
                    tvSysStatusText.setTextColor(getResources().getColor(R.color.accent_green));
                    tvSysStatusDesc.setText("✅ No hazards detected");
                    gasGauge.setIndicatorColor(getResources().getColor(R.color.accent_blue));
                    cardStatus.setStrokeColor(getResources().getColor(R.color.border_color));

                    btnEmergencyShutoff.setVisibility(View.VISIBLE);
                    btnResetSystem.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Shutoff listen failed", error.toException());
            }
        });

        // Monitor Live Gas PPM
        gasRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer currentPpm = snapshot.getValue(Integer.class);
                if (currentPpm != null) {
                    tvGasPpm.setText(String.valueOf(currentPpm));
                    gasGauge.setProgressCompat(currentPpm, true);

                    // Calculate AI risk dynamically on every new reading
                    calculateRiskScore(currentPpm);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Gas listen failed", error.toException());
            }
        });

        // Monitor Flame Status
        flameRef.addValueEventListener(new ValueEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean isFlameOn = snapshot.getValue(Boolean.class);
                if (isFlameOn != null && isFlameOn) {
                    tvFlameStatus.setText("FLAME ON 🔥");
                    tvFlameStatus.setTextColor(Color.parseColor("#EA4335"));
                } else {
                    tvFlameStatus.setText("NO FLAME");
                    tvFlameStatus.setTextColor(Color.parseColor("#5F6368"));
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Flame listen failed", error.toException());
            }
        });

        // Handle Manual Emergency Trigger
        btnEmergencyShutoff.setOnClickListener(v -> {
            shutoffRef.setValue(true).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Toast.makeText(MainActivity.this, "Shutoff Signal Sent!", Toast.LENGTH_SHORT).show();
                }
            });
        });

        // Handle System Reset
        btnResetSystem.setOnClickListener(v -> {
            shutoffRef.setValue(false).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Toast.makeText(MainActivity.this, "System Reset!", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // Calculates risk percentage based on hardware baseline threshold (3500) and rate of change
    private void calculateRiskScore(int currentPpm) {
        double proximityRisk = (currentPpm / 3500.0) * 100.0;
        if (proximityRisk > 100) proximityRisk = 100;

        int ppmJump = currentPpm - previousPpm;
        double velocityRisk = 0;
        if (ppmJump > 0) {
            velocityRisk = (ppmJump / 300.0) * 100.0;
            if (velocityRisk > 100) velocityRisk = 100;
        }

        // Weight distribution: 70% absolute threshold proximity, 30% sudden velocity spike
        double totalRisk = (proximityRisk * 0.7) + (velocityRisk * 0.3);
        int finalRiskPercent = (int) Math.round(totalRisk);

        tvRiskScore.setText(finalRiskPercent + "%");

        if (finalRiskPercent < 40) {
            tvRiskScore.setTextColor(Color.parseColor("#34A853"));
        } else if (finalRiskPercent < 75) {
            tvRiskScore.setTextColor(Color.parseColor("#FBBC05"));
        } else {
            tvRiskScore.setTextColor(Color.parseColor("#EA4335"));
        }

        previousPpm = currentPpm;
    }
}