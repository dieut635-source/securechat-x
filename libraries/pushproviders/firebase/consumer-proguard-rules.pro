# Fix this error:
# ERROR: Missing classes detected while running R8. Add the generated rules from app/build/outputs/mapping/<variant>/missing_rules.txt.
# ERROR: R8: Missing class com.google.firebase.analytics.connector.AnalyticsConnector (referenced from: void com.google.firebase.messaging.MessagingAnalytics.logToScion(java.lang.String, android.os.Bundle) and 1 other context)
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector
