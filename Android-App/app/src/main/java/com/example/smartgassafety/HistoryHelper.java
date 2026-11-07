package com.example.smartgassafety;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shows the leak-history bottom sheet for any history path (used by the user page and the admin page). */
public class HistoryHelper {

    public static void show(Activity activity, DatabaseReference historyRef) {
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(activity);
        View sheetView = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_history, null);
        bottomSheetDialog.setContentView(sheetView);

        LinearLayout llHistoryContainer = sheetView.findViewById(R.id.llHistoryContainer);
        ProgressBar pbHistoryLoading = sheetView.findViewById(R.id.pbHistoryLoading);
        TextView tvEmptyHistory = sheetView.findViewById(R.id.tvEmptyHistory);
        TextView tvCloseHistory = sheetView.findViewById(R.id.tvCloseHistory);

        tvCloseHistory.setOnClickListener(v -> bottomSheetDialog.dismiss());
        bottomSheetDialog.show();

        // Fetch the last 20 logs from Firebase
        historyRef.orderByKey().limitToLast(20).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                pbHistoryLoading.setVisibility(View.GONE);
                llHistoryContainer.removeAllViews();

                if (!snapshot.exists()) {
                    tvEmptyHistory.setVisibility(View.VISIBLE);
                    return;
                }

                // Reverse the list so the newest leak is at the top
                List<DataSnapshot> logList = new ArrayList<>();
                for (DataSnapshot logSnapshot : snapshot.getChildren()) {
                    logList.add(logSnapshot);
                }
                Collections.reverse(logList);

                // Build one card per log
                for (DataSnapshot logSnapshot : logList) {
                    String timestamp = logSnapshot.child("timestamp").getValue(String.class);
                    Integer peakPpm = logSnapshot.child("peak_ppm").getValue(Integer.class);
                    Integer riskScore = logSnapshot.child("risk_score").getValue(Integer.class);
                    Boolean flame = logSnapshot.child("flame_detected").getValue(Boolean.class);

                    View cardView = LayoutInflater.from(activity).inflate(R.layout.item_history_log, llHistoryContainer, false);

                    TextView tvLogTime = cardView.findViewById(R.id.tvLogTime);
                    TextView tvLogRisk = cardView.findViewById(R.id.tvLogRisk);
                    TextView tvLogPpm = cardView.findViewById(R.id.tvLogPpm);
                    TextView tvLogFlame = cardView.findViewById(R.id.tvLogFlame);

                    int risk = (riskScore != null) ? riskScore : 0;
                    tvLogTime.setText(timestamp != null ? timestamp : "Unknown Time");
                    tvLogRisk.setText(risk + "%");
                    tvLogRisk.setTextColor(RiskUtils.colorFor(risk));
                    tvLogPpm.setText((peakPpm != null ? peakPpm : 0) + " PPM");

                    if (flame != null && flame) {
                        tvLogFlame.setVisibility(View.VISIBLE);
                    }

                    llHistoryContainer.addView(cardView);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                pbHistoryLoading.setVisibility(View.GONE);
                Toast.makeText(activity, "Failed to load history.", Toast.LENGTH_SHORT).show();
            }
        });
    }
}