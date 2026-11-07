package com.example.smartgassafety;

import android.graphics.Color;

/** One place for the risk colour rules, so the user page, history cards and admin page always match. */
public class RiskUtils {

    public static int colorFor(int risk) {
        if (risk < 40) return Color.parseColor("#34A853");  // green  = low
        if (risk < 75) return Color.parseColor("#FBBC05");  // yellow = medium
        return Color.parseColor("#EA4335");                 // red    = high
    }
}