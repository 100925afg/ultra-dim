package com.ultradim;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 控制中心磁贴：点一下开关遮罩。 */
public class DimTileService extends TileService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (DimService.isRunning()) {
            DimService.stop(this);
        } else {
            DimService.start(this);
        }
        // 服务启动/停止是异步的，稍后再刷新一次磁贴状态
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateTile();
            }
        }, 350);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = DimService.isRunning();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile));
        tile.updateTile();
    }
}
