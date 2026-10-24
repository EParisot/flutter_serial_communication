package id.farellsujanto.flutter_serial_communication;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.Log;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * FlutterSerialCommunicationPlugin
 */
public class FlutterSerialCommunicationPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware, SerialInputOutputManager.Listener {
  private SerialMessageHandler serialMessageHandler = new SerialMessageHandler();
  private DeviceConnectionHandler deviceConnectionHandler = new DeviceConnectionHandler();

  private SerialInputOutputManager usbIoManager;
  private MethodChannel channel;
  private BinaryMessenger binaryMessenger;
  private UsbSerialPort usbSerialPort;
  private UsbManager usbManager;
  private UsbSerialDriver driver;

  private Result connectResult;
  private int write_wait_millis = 2000;
  private int baudRate = 9600;
  private int dataBits = UsbSerialPort.DATABITS_8;
  private int stopBits = UsbSerialPort.STOPBITS_1;
  private int parity = UsbSerialPort.PARITY_NONE;
  private boolean connected = false;
  private USBGrantReceiver usbGrantReceiver = null;
  private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private FlutterActivity activity;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(),
        "flutter_serial_communication");

    channel.setMethodCallHandler(this);
    binaryMessenger = flutterPluginBinding.getBinaryMessenger();

    new EventChannel(binaryMessenger,
        PluginConfig.SERIAL_STREAM_CHANNEL)
        .setStreamHandler(serialMessageHandler);

    new EventChannel(binaryMessenger,
        PluginConfig.DEVICE_CONNECTION_STREAM_CHANNEL)
        .setStreamHandler(deviceConnectionHandler);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "getAvailableDevices": {
        usbExecutor.execute(() -> {
          String devices = getConvertedAvailableDevices(getAvailableDevices());
          mainHandler.post(() -> result.success(devices));
        });
        break;
      }
      case "write": {
        byte[] data = call.arguments();
        usbExecutor.execute(() -> {
          boolean success = write(data);
          mainHandler.post(() -> result.success(success));
        });
        break;
      }
      case "setRTS": {
        boolean set = call.arguments();
        usbExecutor.execute(() -> {
          boolean success = setRTS(set);
          mainHandler.post(() -> result.success(success));
        });
        break;
      }
      case "setDTR": {
        boolean set = call.arguments();
        usbExecutor.execute(() -> {
          boolean success = setDTR(set);
          mainHandler.post(() -> result.success(success));
        });
        break;
      }
      case "setParameters": {
        int requestedBaudRate = call.argument("baudRate");
        int requestedDataBits = call.argument("dataBits");
        int requestedStopBits = call.argument("stopBits");
        int requestedParity = call.argument("parity");
        usbExecutor.execute(() -> {
          boolean success = setParameters(
              requestedBaudRate, requestedDataBits, requestedStopBits, requestedParity);
          mainHandler.post(() -> result.success(success));
        });
        break;
      }
      case "connect": {
        String name = call.argument("name");
        int requestedBaudRate = call.argument("baudRate");
        usbExecutor.execute(() -> {
          if (connectResult != null || connected) {
            mainHandler.post(() -> result.success(false));
            return;
          }
          connectResult = result;
          connect(name, requestedBaudRate);
        });
        break;
      }
      case "disconnect": {
        usbExecutor.execute(() -> {
          disconnect();
          mainHandler.post(() -> result.success(true));
        });
        break;
      }
      case "purgeHwBuffers": {
        boolean purgeWrite = call.argument("purgeWriteBuffers");
        boolean purgeRead = call.argument("purgeReadBuffers");
        usbExecutor.execute(() -> {
          boolean success = purgeHwBuffers(purgeWrite, purgeRead);
          mainHandler.post(() -> result.success(success));
        });
        break;
      }
      default: {
        result.notImplemented();
      }
    }
  }

  private boolean setRTS(boolean set) {
    boolean success = false;
    if(usbSerialPort != null) {
      try {
        usbSerialPort.setRTS(set);
        success = true;
      } catch (IOException exception) {
        Log.e("Serial", "Failed to set RTS", exception);
      }
    }
    return success;
  }

  private boolean setDTR(boolean set) {
    boolean success = false;
    if(usbSerialPort != null) {
      try {
        usbSerialPort.setDTR(set);
        success = true;
      } catch (IOException exception) {
        Log.e("Serial", "Failed to set DTR", exception);
      }
    }
    return success;
  }

  private boolean setParameters(int baudRate, int dataBits, int stopBits, int parity) {
    boolean success = false;
    if(usbSerialPort != null) {
      try {
        usbSerialPort.setParameters(baudRate, dataBits, stopBits, parity);
        success = true;
      } catch (IOException exception) {
        Log.e("Serial", "Failed to set serial parameters", exception);
      }
    }
    return success;
  }

  private boolean purgeHwBuffers(boolean purgeWriteBuffers, boolean purgeReadBuffers) {
    boolean success = false;
    if(usbSerialPort != null) {
      try {
        usbSerialPort.purgeHwBuffers(purgeWriteBuffers, purgeReadBuffers);
        success = true;
      } catch (IOException exception) {
        Log.e("Serial", "Failed to purge serial buffers", exception);
      }
    }
    return success;
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    usbExecutor.execute(() -> {
      disconnect();
      usbExecutor.shutdown();
    });
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activity = (FlutterActivity) binding.getActivity();
    usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
    // TODO: Application cold started by an ACTION_USB_DEVICE_ATTACHED intent - propagate to Flutter?
    // TODO: For example connect to the device:
    // Intent intent = activity.getIntent();
    // String action = intent.getAction();
    // if(action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
    //   UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    //   Log.i("Action", action);
    //   Log.i("UsbDevice", device.getDeviceName());
    //   connect(device.getDeviceName());
    // }
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {}

  @Override
  public void onDetachedFromActivity() {}

  private List<DeviceInfo> getAvailableDevices() {
    List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

    List<DeviceInfo> deviceInfoList = new ArrayList<>(availableDrivers.size());

    for (UsbSerialDriver driver : availableDrivers) {
      UsbDevice device = driver.getDevice();
      deviceInfoList.add(new DeviceInfo(device));
    }

    return deviceInfoList;
  }

  private String getConvertedAvailableDevices(List<DeviceInfo> deviceInfoList) {
    List<Map<String, String>> convertedDeviceInfoList = new ArrayList<>(deviceInfoList.size());

    for (DeviceInfo deviceInfo : deviceInfoList) {
      convertedDeviceInfoList.add(deviceInfo.toMap());
    }

    return convertedDeviceInfoList.toString();
  }

  private boolean write(byte[] data) {
    if (usbSerialPort == null) {
      Log.e("Serial", "Cannot write without an open USB serial port");
      return false;
    }
    try {
      usbSerialPort.write(data, write_wait_millis);
      return true;
    } catch (IOException e) {
      Log.e("Error Sending", e.getMessage());
      disconnect();
    }
    return false;
  }

  private void connect(String name, int baudRate) {
    this.baudRate = baudRate;
    if (connected) {
      handleConnectResult(false);
      return;
    }

    List<UsbSerialDriver> availableDrivers = UsbSerialProber
        .getDefaultProber().findAllDrivers(usbManager);
    List<DeviceInfo> availableDevices = getAvailableDevices();
    if (availableDrivers.size() == 0 || availableDevices.size() == 0) {
      handleConnectResult(false);
      return;
    }

    int index = -1;
    for (int i = 0; i < availableDevices.size(); i++) {
      if (availableDevices.get(i).deviceName.equals(name)) {
        index = i;
        break;
      }
    }

    if (index < 0) {
      handleConnectResult(false);
      return;
    }

    driver = availableDrivers.get(index);

    if (usbManager.hasPermission(driver.getDevice()) == false) {
      mainHandler.post(() -> requestUsbPermission());
    } else {
      openPort(baudRate);
    }
  }

  private void requestUsbPermission() {
    if (activity == null || driver == null) {
      usbExecutor.execute(() -> handleConnectResult(false));
      return;
    }

    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ? PendingIntent.FLAG_MUTABLE : 0;
    usbGrantReceiver = new USBGrantReceiver(this);
    if (Build.VERSION.SDK_INT >= 34) {
      activity.registerReceiver(
          usbGrantReceiver,
          new IntentFilter(PluginConfig.INTENT_ACTION_GRANT_USB),
          Context.RECEIVER_NOT_EXPORTED);
    } else {
      activity.registerReceiver(
          usbGrantReceiver,
          new IntentFilter(PluginConfig.INTENT_ACTION_GRANT_USB));
    }

    PendingIntent usbGrantIntent = PendingIntent.getBroadcast(activity,
        0,
        new Intent(PluginConfig.INTENT_ACTION_GRANT_USB).setPackage(activity.getPackageName()),
        flags);
    usbManager.requestPermission(driver.getDevice(), usbGrantIntent);
  }

  public void openPort() {
    openPort(baudRate);
  }

  private void openPort(int baudRate) {
    usbExecutor.execute(() -> openPortOnUsbThread(baudRate));
  }

  private void openPortOnUsbThread(int baudRate) {
    try {
      if (driver == null || driver.getPorts().isEmpty()) {
        handleConnectResult(false);
        return;
      }
      usbSerialPort = driver.getPorts().get(0);
      //usbSerialPort.getControlLines();
	  usbSerialPort.getSupportedControlLines();

      UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
      if (usbConnection == null) {
		Log.w("Serial", "usbManager.openDevice returned null");
		handleConnectResult(false);
		return;
	  }
	  usbSerialPort.open(usbConnection);

      usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
      connected = true;
      mainHandler.post(() -> deviceConnectionHandler.success(true));
      usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
      usbIoManager.start();

      handleConnectResult(true);
      return;
    } catch (IOException e) {
      Log.e("Serial", "Failed to open USB serial port", e);
      disconnect();
    }

    handleConnectResult(false);
  }

  public void onUsbPermissionDenied() {
    usbExecutor.execute(() -> handleConnectResult(false));
  }

  private void disconnect() {
    connected = false;
    if (connectResult != null) {
      handleConnectResult(false);
    }
    mainHandler.post(() -> deviceConnectionHandler.success(false));
    try {
      if (usbIoManager != null) {
        usbIoManager.setListener(null);
        usbIoManager.stop();
      }
      usbIoManager = null;
      if(usbSerialPort != null && usbSerialPort.isOpen()) {
       usbSerialPort.close();
      }
    } catch (IOException exception) {
      Log.e("Serial", "Failed to close USB serial port", exception);
    }
  }

  private void handleConnectResult(boolean value) {
    Result result = connectResult;
    connectResult = null;
    mainHandler.post(() -> {
      if (result != null) {
        result.success(value);
      }
      if (usbGrantReceiver != null && activity != null) {
        activity.unregisterReceiver(usbGrantReceiver);
        usbGrantReceiver = null;
      }
    });
  }



  @Override
  public void onNewData(byte[] data) {
    activity.runOnUiThread(() -> {
      serialMessageHandler.success(data);
    });
  }

  @Override
  public void onRunError(Exception e) {
    Log.e("Serial", "USB serial I/O failed", e);
    usbExecutor.execute(this::disconnect);
  }
}
