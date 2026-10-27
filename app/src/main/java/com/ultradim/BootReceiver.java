package com.ultradim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机 / 应用更新后，按上次的开关状态自动恢复遮罩。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            State state = new State(context);
            if (!state.isEnabled()) return;
            if (!Settings.canDrawOverlays(context)) {
                state.setEnabled(false);
                return;
            }
            DimService.start(context);
        } catch (Throwable ignored) {
            // Android 14+ 对开机拉起前台服务有限制，失败就等用户手动打开
        }
    }
}
