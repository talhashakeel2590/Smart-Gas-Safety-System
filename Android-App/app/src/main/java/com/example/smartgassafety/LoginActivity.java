package com.example.smartgassafety;

import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.onesignal.OneSignal;

public class LoginActivity extends AppCompatActivity {
    private FirebaseAuth mAuth;
    private android.widget.ProgressBar loginProgressBar;
    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnLogin;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // 1. Initialize OneSignal FIRST (before any early returns or logins)
        OneSignal.initWithContext(this, "489ba8b0-185f-49d8-bc1a-d2a5d87e4e2c");
        OneSignal.getNotifications().requestPermission(true, com.onesignal.Continue.none());

        // 2. Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // 3. Auto-login check
        if (mAuth.getCurrentUser() != null) {
            checkUserRole(mAuth.getCurrentUser().getUid());
            return; // Stops here, goes to dashboard
        }

        // 4. Only logout OneSignal if we are actually showing the login screen
        OneSignal.logout();

        loginProgressBar = findViewById(R.id.loginProgressBar);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(LoginActivity.this, "Please enter email and password", Toast.LENGTH_SHORT).show();
                return;
            }

            // Hide text, disable button, show spinner
            btnLogin.setText("");
            btnLogin.setEnabled(false);
            loginProgressBar.setVisibility(android.view.View.VISIBLE);

            loginUser(email, password);
        });
    }

    private void loginUser(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            checkUserRole(user.getUid());
                        }
                    }
                    else {
                        // Reset UI on failure
                        btnLogin.setText("LOG IN");
                        btnLogin.setEnabled(true);
                        loginProgressBar.setVisibility(android.view.View.GONE);
                        Toast.makeText(LoginActivity.this, "Authentication failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void checkUserRole(String uid) {
        // Save the email so the admin page can list this user
        String email = mAuth.getCurrentUser().getEmail();
        FirebaseDatabase.getInstance().getReference("Users").child(uid).child("email").setValue(email);

        DatabaseReference roleRef = FirebaseDatabase.getInstance().getReference().child("Users").child(uid).child("role");

        roleRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String role = snapshot.getValue(String.class);
                boolean isAdmin = "admin".equals(role);
                OneSignal.login(uid);
                OneSignal.getUser().addTag("house", uid);
                if (isAdmin) {
                    OneSignal.getUser().addTag("role", "admin");
                } else {
                    OneSignal.getUser().removeTag("role");
                }

                if (isAdmin) {
                    Toast.makeText(LoginActivity.this, "Admin Authenticated (Monitor Mode)", Toast.LENGTH_SHORT).show();
                } else {
                    // Defaults to user if role is missing or set to "user"
                    Toast.makeText(LoginActivity.this, "User Authenticated (Control Mode)", Toast.LENGTH_SHORT).show();
                }

                // Admins go to the admin page, everyone else to the normal user page
                Intent intent = new Intent(LoginActivity.this, isAdmin ? AdminActivity.class : MainActivity.class);
                startActivity(intent);
                finish();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(LoginActivity.this, "Database error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}