package com.example.smartgassafety;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Intent;
import com.google.firebase.auth.FirebaseAuth;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.auth.FirebaseAuth;
import com.onesignal.OneSignal;
import java.util.ArrayList;
import java.util.List;

/** Admin page: shows every user's AI risk and leak history. It never reads gas, flame or valve data. */
public class AdminActivity extends AppCompatActivity {

    private FirebaseDatabase database;
    private LinearLayout llUsers;
    private TextView tvNoUsers;
    private final List<DatabaseReference> userRefs = new ArrayList<>();
    private final List<ValueEventListener> userListeners = new ArrayList<>();
    private Button btnLogout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        llUsers = findViewById(R.id.llUsers);
        tvNoUsers = findViewById(R.id.tvNoUsers);
        database = FirebaseDatabase.getInstance();
        btnLogout = findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> {
            OneSignal.getUser().removeTag("house");
            OneSignal.getUser().removeTag("role");
            OneSignal.logout();
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        loadUsers();
    }

    // Reads the list of users once and builds one card per user
    private void loadUsers() {
        database.getReference("Users").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                llUsers.removeAllViews();
                boolean foundUser = false;

                for (DataSnapshot userSnapshot : snapshot.getChildren()) {
                    String role = userSnapshot.child("role").getValue(String.class);
                    if ("admin".equals(role)) continue; // list normal users only

                    String email = userSnapshot.child("email").getValue(String.class);
                    addUserCard(userSnapshot.getKey(), email != null ? email : "Unknown user");
                    foundUser = true;
                }

                tvNoUsers.setVisibility(foundUser ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Users load failed", error.toException());
            }
        });
    }

    // Builds one user card and attaches its live listeners
    @SuppressLint("SetTextI18n")
    private void addUserCard(String uid, String email) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_user_card, llUsers, false);

        TextView tvUserEmail = card.findViewById(R.id.tvUserEmail);
        TextView tvUserLeak = card.findViewById(R.id.tvUserLeak);
        TextView tvUserRisk = card.findViewById(R.id.tvUserRisk);
        Button btnUserHistory = card.findViewById(R.id.btnUserHistory);

        tvUserEmail.setText(email);
        btnUserHistory.setOnClickListener(v ->
                HistoryHelper.show(this, database.getReference("History").child(uid)));

        // Live AI risk score of this user
        DatabaseReference riskRef = database.getReference("Devices").child(uid).child("risk_score");
        ValueEventListener riskListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer risk = snapshot.getValue(Integer.class);
                if (risk == null) { tvUserRisk.setText("--"); return; }
                tvUserRisk.setText(risk + "%");
                tvUserRisk.setTextColor(RiskUtils.colorFor(risk));
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Risk listen failed", error.toException());
            }
        };
        riskRef.addValueEventListener(riskListener);
        userRefs.add(riskRef);
        userListeners.add(riskListener);

        DatabaseReference leakRef = database.getReference("Devices").child(uid).child("leak_detected");
        ValueEventListener leakListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean leak = snapshot.getValue(Boolean.class);
                tvUserLeak.setVisibility((leak != null && leak) ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Leak listen failed", error.toException());
            }
        };
        leakRef.addValueEventListener(leakListener);
        userRefs.add(leakRef);
        userListeners.add(leakListener);

        llUsers.addView(card);
    }

    @Override
    protected void onDestroy() {
        for (int i = 0; i < userRefs.size(); i++) {
            userRefs.get(i).removeEventListener(userListeners.get(i));
        }
        super.onDestroy();
    }
}