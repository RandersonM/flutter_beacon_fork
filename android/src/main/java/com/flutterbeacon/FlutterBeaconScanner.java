package com.flutterbeacon;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.RemoteException;
import android.os.Looper;
import android.os.Handler;
import android.util.Log;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.MonitorNotifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;

class FlutterBeaconScanner {
  private static final String TAG = FlutterBeaconScanner.class.getSimpleName();
  private final FlutterBeaconPlugin plugin;
  private final WeakReference<Activity> activity;

  private Handler handler;
  private boolean isBluetoothCoordinated = false;

  private EventChannel.EventSink eventSinkRanging;
  private EventChannel.EventSink eventSinkMonitoring;
  private List<Region> regionRanging;
  private List<Region> regionMonitoring;

  public FlutterBeaconScanner(FlutterBeaconPlugin plugin, Activity activity) {
    this.plugin = plugin;
    this.activity = new WeakReference<>(activity);
    handler = new Handler(Looper.getMainLooper());
  }

  /**
   * Coordinate with other Bluetooth libraries to avoid conflicts
   */
  private void coordinateBluetoothUsage() {
    if (isBluetoothCoordinated) {
      return;
    }
    
    try {
      Log.d(TAG, "Coordinating Bluetooth usage with other libraries...");
      
      // Wait a bit to let other Bluetooth operations finish
      Thread.sleep(1000);
      
      // Check if Bluetooth is available
      BluetoothManager bluetoothManager = (BluetoothManager) activity.get().getSystemService(Context.BLUETOOTH_SERVICE);
      if (bluetoothManager != null) {
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
          Log.d(TAG, "Bluetooth is enabled and ready for beacon scanning");
        } else {
          Log.w(TAG, "Bluetooth is not enabled");
        }
      }
      
      isBluetoothCoordinated = true;
      Log.d(TAG, "Bluetooth coordination completed");
    } catch (Exception e) {
      Log.e(TAG, "Error coordinating Bluetooth usage: " + e.getMessage());
    }
  }

  final EventChannel.StreamHandler rangingStreamHandler = new EventChannel.StreamHandler() {
    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
      Log.d("RANGING", "Start ranging = " + o);
      coordinateBluetoothUsage();
      startRanging(o, eventSink);
    }

    @Override
    public void onCancel(Object o) {
      Log.d("RANGING", "Stop ranging = " + o);
      stopRanging();
    }
  };

  @SuppressWarnings("rawtypes")
  private void startRanging(Object o, EventChannel.EventSink eventSink) {
    if (o instanceof List) {
      List list = (List) o;
      if (regionRanging == null) {
        regionRanging = new ArrayList<>();
      } else {
        regionRanging.clear();
      }
      for (Object object : list) {
        if (object instanceof Map) {
          Map map = (Map) object;
          Region region = FlutterBeaconUtils.regionFromMap(map);
          if (region != null) {
            regionRanging.add(region);
          }
        }
      }
    } else {
      eventSink.error("Beacon", "invalid region for ranging", null);
      return;
    }
    eventSinkRanging = eventSink;
    if (plugin.getBeaconManager() != null && !plugin.getBeaconManager().isBound(beaconConsumer)) {
      plugin.getBeaconManager().bind(beaconConsumer);
    } else {
      startRanging();
    }
  }

  void startRanging() {
    if (regionRanging == null || regionRanging.isEmpty()) {
      Log.e("RANGING", "Region ranging is null or empty. Ranging not started.");
      return;
    }

    try {
      if (plugin.getBeaconManager() != null) {
        plugin.getBeaconManager().removeAllRangeNotifiers();
        plugin.getBeaconManager().addRangeNotifier(rangeNotifier);
        for (Region region : regionRanging) {
          plugin.getBeaconManager().startRangingBeaconsInRegion(region);
        }
      }
    } catch (RemoteException e) {
      Log.e("RANGING", "Error starting ranging: " + e.getMessage());
    }
  }

  void stopRanging() {
    if (regionRanging != null && !regionRanging.isEmpty()) {
      try {
        for (Region region : regionRanging) {
          plugin.getBeaconManager().stopRangingBeaconsInRegion(region);
        }
        plugin.getBeaconManager().removeAllRangeNotifiers();
      } catch (RemoteException ignored) {
      }
    }
    eventSinkRanging = null;
  }

  private final RangeNotifier rangeNotifier = new RangeNotifier() {
    @Override
    public void didRangeBeaconsInRegion(Collection<Beacon> collection, Region region) {
      if (eventSinkRanging != null) {
        final Map<String, Object> map = new HashMap<>();
        map.put("region", FlutterBeaconUtils.regionToMap(region));
        map.put("beacons", FlutterBeaconUtils.beaconsToArray(collection));
        handler.post(new Runnable(){
          @Override
          public void run() {
            if(eventSinkRanging != null){
              eventSinkRanging.success(map);
            }
          }
        });
      }
    }
  };

  final EventChannel.StreamHandler monitoringStreamHandler = new EventChannel.StreamHandler() {
    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
      Log.d("MONITORING", "Start monitoring = " + o);
      coordinateBluetoothUsage();
      startMonitoring(o, eventSink);
    }

    @Override
    public void onCancel(Object o) {
      Log.d("MONITORING", "Stop monitoring = " + o);
      stopMonitoring();
    }
  };

  @SuppressWarnings("rawtypes")
  private void startMonitoring(Object o, EventChannel.EventSink eventSink) {
    if (o instanceof List) {
      List list = (List) o;
      if (regionMonitoring == null) {
        regionMonitoring = new ArrayList<>();
      } else {
        regionMonitoring.clear();
      }
      for (Object object : list) {
        if (object instanceof Map) {
          Map map = (Map) object;
          Region region = FlutterBeaconUtils.regionFromMap(map);
          if (region != null) {
            regionMonitoring.add(region);
          }
        }
      }
    } else {
      eventSink.error("Beacon", "invalid region for monitoring", null);
      return;
    }
    eventSinkMonitoring = eventSink;
    if (plugin.getBeaconManager() != null && !plugin.getBeaconManager().isBound(beaconConsumer)) {
      plugin.getBeaconManager().bind(beaconConsumer);
    } else {
      startMonitoring();
    }
  }

  void startMonitoring() {
    if (regionMonitoring == null || regionMonitoring.isEmpty()) {
      Log.e("MONITORING", "Region monitoring is null or empty. Monitoring not started.");
      return;
    }

    try {
      if (plugin.getBeaconManager() != null) {
        plugin.getBeaconManager().removeAllMonitorNotifiers();
        plugin.getBeaconManager().addMonitorNotifier(monitorNotifier);
        for (Region region : regionMonitoring) {
          plugin.getBeaconManager().startMonitoringBeaconsInRegion(region);
        }
      }
    } catch (RemoteException e) {
      Log.e("MONITORING", "Error starting monitoring: " + e.getMessage());
    }
  }

  void stopMonitoring() {
    if (regionMonitoring != null && !regionMonitoring.isEmpty()) {
      try {
        for (Region region : regionMonitoring) {
          plugin.getBeaconManager().stopMonitoringBeaconsInRegion(region);
        }
        plugin.getBeaconManager().removeMonitorNotifier(monitorNotifier);
      } catch (RemoteException ignored) {
      }
    }
    eventSinkMonitoring = null;
  }

  private final MonitorNotifier monitorNotifier = new MonitorNotifier() {
    @Override
    public void didEnterRegion(Region region) {
      if (eventSinkMonitoring != null) {
        final Map<String, Object> map = new HashMap<>();
        map.put("event", "didEnterRegion");
        map.put("region", FlutterBeaconUtils.regionToMap(region));
        handler.post(new Runnable(){
          @Override
          public void run() {
            if(eventSinkMonitoring != null){
              eventSinkMonitoring.success(map);
            }
          }
        });
      }
    }

    @Override
    public void didExitRegion(Region region) {
      if (eventSinkMonitoring != null) {
        final Map<String, Object> map = new HashMap<>();
        map.put("event", "didExitRegion");
        map.put("region", FlutterBeaconUtils.regionToMap(region));
        handler.post(new Runnable(){
          @Override
          public void run() {
            if(eventSinkMonitoring != null){
              eventSinkMonitoring.success(map);
            }
          }
        });
      }
    }

    @Override
    public void didDetermineStateForRegion(int state, Region region) {
      if (eventSinkMonitoring != null) {
        final Map<String, Object> map = new HashMap<>();
        map.put("event", "didDetermineStateForRegion");
        map.put("state", FlutterBeaconUtils.parseState(state));
        map.put("region", FlutterBeaconUtils.regionToMap(region));
        handler.post(new Runnable(){
          @Override
          public void run() {
            if(eventSinkMonitoring != null){
              eventSinkMonitoring.success(map);
            }
          }
        });
      }
    }
  };

  final BeaconConsumer beaconConsumer = new BeaconConsumer() {
    @Override
    public void onBeaconServiceConnect() {
      if (plugin.flutterResult != null) {
        plugin.flutterResult.success(true);
        plugin.flutterResult = null;
      } else {
        startRanging();
        startMonitoring();
      }
    }

    @Override
    public Context getApplicationContext() {
      return activity.get().getApplicationContext();
    }

    @Override
    public void unbindService(ServiceConnection serviceConnection) {
      activity.get().unbindService(serviceConnection);
    }

    @Override
    public boolean bindService(Intent intent, ServiceConnection serviceConnection, int i) {
      return activity.get().bindService(intent, serviceConnection, i);
    }
  };
}
