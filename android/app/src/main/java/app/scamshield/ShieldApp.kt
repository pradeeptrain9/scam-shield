package app.scamshield

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

class ShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before any Firebase AI call. Logcat prints the debug secret to register in
        // Firebase console > App Check > Apps > Manage debug tokens.
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
