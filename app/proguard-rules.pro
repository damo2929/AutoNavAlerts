# Incident reporting is looked up by reflection on the Android Auto map view (IncidentReporting.kt),
# so keep those method names if a future Navigation SDK adds them.
-keepclassmembers class com.google.android.libraries.navigation.NavigationViewForAuto {
    public *** setReportIncidentButtonEnabled(...);
    public *** showReportIncidentsPanel(...);
    public *** isIncidentReportingAvailable(...);
}

# The Navigation SDK creates some of its own classes by reflection; shrinking it breaks that
# (release build on a phone, 2026-10-12: IllegalAccessException "…navigation.internal.amv.k is not
# accessible from jv6" five seconds after the Maps key was saved). Keep it whole.
-keep class com.google.android.libraries.navigation.** { *; }
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.libraries.navigation.**
