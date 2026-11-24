package nl.meereboer.ebikelive.phone

import android.media.AudioManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives volume steps from the watch. Uses the same route as the phone's
 * own volume buttons, so it follows the active media session: Spotify on the
 * phone, Bluetooth headphones, or a Spotify Connect speaker.
 */
class VolumeListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/ebike/volume") return
        val up = event.data.firstOrNull() == 1.toByte()
        getSystemService(AudioManager::class.java)?.adjustSuggestedStreamVolume(
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            0,
        )
    }
}
