package com.naman14.timberx.sdl;

import android.util.Log;

public class SdlRouterService extends  com.smartdevicelink.transport.SdlRouterService {
    private final String TAG = "SdlRouterService";

    public SdlRouterService() {
        Log.i(TAG, "SdlRouterService: ");
    }

    @Override
    public void resetForegroundTimeOut(long delay) {
        //if (!getResources().getBoolean(R.bool.custom_rs)) {
            super.resetForegroundTimeOut(delay);
        //}
    }

    @Override
    public void closeSelf() {
        //if (!getResources().getBoolean(R.bool.custom_rs)){
            super.closeSelf();
        //}
    }
}