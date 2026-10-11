# Incident reporting is looked up by reflection on the Android Auto map view (IncidentReporting.kt),
# so keep those method names if a future Navigation SDK adds them.
-keepclassmembers class com.google.android.libraries.navigation.NavigationViewForAuto {
    public *** setReportIncidentButtonEnabled(...);
    public *** showReportIncidentsPanel(...);
    public *** isIncidentReportingAvailable(...);
}
