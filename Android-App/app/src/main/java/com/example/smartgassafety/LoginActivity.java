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

public class LoginActivity extends AppCompatActivity {
    private FirebaseAuth mAuth;
    private android.widget.ProgressBar loginProgressBar;
    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnLogin;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();
        loginProgressBar = findViewById(R.id.loginProgressBar);
        // Connect UI elements
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
        DatabaseReference roleRef = FirebaseDatabase.getInstance().getReference().child("Users").child(uid).child("role");

        roleRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String role = snapshot.getValue(String.class);

                if ("admin".equals(role)) {
                    Toast.makeText(LoginActivity.this, "Admin Authenticated (Monitor Mode)", Toast.LENGTH_SHORT).show();
                } else {
                    // Defaults to user if role is missing or set to "user"
                    Toast.makeText(LoginActivity.this, "User Authenticated (Control Mode)", Toast.LENGTH_SHORT).show();
                }

                // Temporarily route everyone to the current MainActivity to verify the login works
                Intent intent = new Intent(LoginActivity.this, MainActivity.class);
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
