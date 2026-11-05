package com.example.smartgassafety;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class MainActivity extends AppCompatActivity {

    private TextView tvGasPpm, tvSysStatusText, tvSysStatusDesc, tvFlameStatus;
    private DatabaseReference shutoffRef;
    private CircularProgressIndicator gasGauge;
    private com.google.android.material.card.MaterialCardView cardStatus;
    private Button btnEmergencyShutoff, btnResetSystem;

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

        // 2. Initialize Firebase Reference
        shutoffRef = FirebaseDatabase.getInstance().getReference("Device/emergency_shutoff");

        // 3. Real-Time Firebase Listener & UI Updater
        shutoffRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                Boolean isHazard = snapshot.getValue(Boolean.class);

                if (isHazard != null && isHazard) {
                    // HAZARD STATE UI
                    tvSysStatusText.setText("HAZARD");
                    tvSysStatusText.setTextColor(getResources().getColor(R.color.accent_red));
                    tvSysStatusDesc.setText("⚠️ Emergency Shutoff Active");
                    gasGauge.setIndicatorColor(getResources().getColor(R.color.accent_red));
                    cardStatus.setStrokeColor(getResources().getColor(R.color.accent_red));

                    btnEmergencyShutoff.setVisibility(View.GONE);
                    btnResetSystem.setVisibility(View.VISIBLE);
                } else {
                    // NORMAL STATE UI
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
            public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                android.util.Log.e("Firebase", "Listen failed", error.toException());
            }
        });

        // 4. Emergency Shutoff Button Click
        btnEmergencyShutoff.setOnClickListener(v -> {
            shutoffRef.setValue(true).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Toast.makeText(MainActivity.this, "Shutoff Signal Sent!", Toast.LENGTH_SHORT).show();
                }
            });
        });

        // 5. System Reset Button Click
        btnResetSystem.setOnClickListener(v -> {
            shutoffRef.setValue(false).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Toast.makeText(MainActivity.this, "System Reset!", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}