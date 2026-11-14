// 짝 워치가 Data Layer 로 보낸 워치 상태를 받는 서비스 — 받은 값은 WatchLink 에 두고 sync 가 가져간다
package com.example.guardian.phone.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchLink.STATE_PATH) WatchLink.onBytes(event.data)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != WatchLink.STATE_PATH) continue
            DataMapItem.fromDataItem(e.dataItem).dataMap.getString(WatchLink.DATA_KEY)?.let { WatchLink.onBytes(it.toByteArray(Charsets.UTF_8)) }
        }
    }
}
