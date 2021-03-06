/*
 * Copyright 2015 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uribeacon.validator;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.uribeacon.beacon.UriBeacon;
import org.uribeacon.config.ProtocolV2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class TestHelper {

  private static final String TAG = TestHelper.class.getCanonicalName();
  private final ScanCallback mScanCallback = new ScanCallback() {
    @Override
    public void onScanResult(int callbackType, ScanResult result) {
      super.onScanResult(callbackType, result);
      // First time we see the beacon
      Log.d(TAG, "On scan Result");
      if(mScanResultSet.add(result.getDevice())) {
        mScanResults.add(result);
      }
    }
  };
  private boolean started;
  private boolean failed;
  private boolean finished;
  private boolean disconnected;
  private boolean stopped;
  private BluetoothGatt mGatt;
  private final long SCAN_TIMEOUT = TimeUnit.SECONDS.toMillis(5);
  private BluetoothGattService mService;
  private final String mName;
  private final String mReference;
  private final Context mContext;
  private BluetoothDevice mBluetoothDevice;
  private final UUID mServiceUuid;
  private BluetoothGattCallback mOutSideGattCallback;
  private HashSet<BluetoothDevice> mScanResultSet;
  private ArrayList<ScanResult> mScanResults;

  public final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
      super.onConnectionStateChange(gatt, status, newState);
      Log.d(TAG, "Status: " + status + "; New State: " + newState);
      mGatt = gatt;
      if (status == BluetoothGatt.GATT_SUCCESS) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
          mGatt.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
          if (!failed) {
            mTestActions.remove();
            disconnected = true;
            mGatt = null;
            dispatch();
          }
        }
      } else {
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
          mGatt = null;
        }
        fail("Failed. Status: " + status + ". New State: " + newState);
      }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
      super.onServicesDiscovered(gatt, status);
      Log.d(TAG, "On services discovered");
      mGatt = gatt;
      mService = mGatt.getService(mServiceUuid);
      if (mTestActions.peek().actionType == TestAction.CONNECT) {
        mTestActions.remove();
      }
      mTestCallback.connectedToBeacon();
      dispatch();
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
        int status) {
      super.onCharacteristicRead(gatt, characteristic, status);
      Log.d(TAG, "On characteristic read");
      mGatt = gatt;
      TestAction readTest = mTestActions.peek();
      byte[] value = characteristic.getValue();
      int actionType = readTest.actionType;
      if (readTest.expectedReturnCode != status) {
        fail("Incorrect status code: " + status + ". Expected: " + readTest.expectedReturnCode);
      } else if (actionType == TestAction.ASSERT_NOT_EQUALS
          && Arrays.equals(readTest.transmittedValue, value)) {
        fail("Values read are the same: " + Arrays.toString(value));
      } else if (actionType == TestAction.ASSERT
          && !Arrays.equals(readTest.transmittedValue, value)) {
        fail("Result not the same. Expected: " + Arrays.toString(readTest.transmittedValue)
            + ". Received: " + Arrays.toString(value));
      } else {
        mTestActions.remove();
        dispatch();
      }
    }


    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt,
        BluetoothGattCharacteristic characteristic,
        int status) {
      super.onCharacteristicWrite(gatt, characteristic, status);
      Log.d(TAG, "On write");
      mGatt = gatt;
      TestAction writeTest = mTestActions.peek();
      int actionType = writeTest.actionType;
      if (actionType == TestAction.WRITE &&  writeTest.expectedReturnCode != status) {
        fail("Incorrect status code: " + status + ". Expected: " + writeTest.expectedReturnCode);
      } else if (actionType == TestAction.MULTIPLE_VALID_RETURN_CODES) {
        boolean match = false;
        for (int expected : writeTest.expectedReturnCodes) {
          if (expected == status) {
            match = true;
          }
        }
        if (!match) {
          fail("Error code didn't match any of the expected error codes.");
        } else {
          mTestActions.remove();
          dispatch();
        }
      } else {
        mTestActions.remove();
        dispatch();
      }
    }

  };
  private final TestCallback mTestCallback;
  private LinkedList<TestAction> mTestActions;
  private final LinkedList<TestAction> mTestSteps;
  private final BluetoothAdapter mBluetoothAdapter;
  private final Handler mHandler;


  private TestHelper(
      String name, String reference, Context context, UUID serviceUuid,
      TestCallback testCallback, LinkedList<TestAction> testActions,
      LinkedList<TestAction> testSteps) {
    mName = name;
    mReference = reference;
    mContext = context;
    mServiceUuid = serviceUuid;
    mTestCallback = testCallback;
    mTestActions = testActions;
    mTestSteps = testSteps;
    final BluetoothManager bluetoothManager =
        (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = bluetoothManager.getAdapter();
    mHandler = new Handler(Looper.myLooper());
  }

  public String getName() {
    return mName;
  }

  public LinkedList<TestAction> getTestSteps() {
    return mTestSteps;
  }

  public boolean isFailed() {
    return failed;
  }

  public boolean isStarted() {
    return started;
  }

  public void run(BluetoothDevice bluetoothDevice, BluetoothGatt gatt,
      BluetoothGattCallback outsideCallback) {
    Log.d(TAG, "Run Called for: " + getName());
    started = true;
    failed = false;
    finished = false;
    disconnected = false;
    stopped = false;
    mScanResults = new ArrayList<>();
    mScanResultSet = new HashSet<>();
    mBluetoothDevice = bluetoothDevice;
    mGatt = gatt;
    if (mGatt != null) {
      mService = gatt.getService(mServiceUuid);
    }
    mTestCallback.testStarted();
    mOutSideGattCallback = outsideCallback;
    dispatch();
  }

  private void connectToGatt() {
    Log.d(TAG, "Connecting");

    if (disconnected) {
      try {
        disconnected = false;
        // We have to wait before trying to connect
        // Else the connection is not successful
        TimeUnit.SECONDS.sleep(1);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    mTestCallback.waitingForConfigMode();
    // First time the device is seen
    if (mBluetoothDevice == null) {
      scanForBeacon();
    } else {
      mBluetoothDevice.connectGatt(mContext, false, mOutSideGattCallback);
    }
  }

  private void readFromGatt() {
    Log.d(TAG, "reading");
    TestAction readTest = mTestActions.peek();
    BluetoothGattCharacteristic characteristic = mService
        .getCharacteristic(readTest.characteristicUuid);
    mGatt.readCharacteristic(characteristic);
  }

  private void writeToGatt() {
    Log.d(TAG, "Writing");
    TestAction writeTest = mTestActions.peek();
    BluetoothGattCharacteristic characteristic = mService
        .getCharacteristic(writeTest.characteristicUuid);
    // WriteType is WRITE_TYPE_NO_RESPONSE even though the one that requests a response
    // is called WRITE_TYPE_DEFAULT!
    if (characteristic.getWriteType() != BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
      Log.w(TAG, "writeCharacteristic default WriteType is being forced to WRITE_TYPE_DEFAULT");
      characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }
    characteristic.setValue(writeTest.transmittedValue);
    mGatt.writeCharacteristic(characteristic);
  }

  private void disconnectFromGatt() {
    Log.d(TAG, "Disconnecting");
    mGatt.disconnect();
  }

  private void dispatch() {
    Log.d(TAG, "Dispatching");
    Log.d(TAG, "Stopped: " + stopped);
    for (TestAction test : mTestActions) {
      Log.d(TAG, "Test action: " + test.actionType);
    }
    int actionType = mTestActions.peek().actionType;
    // If the test is stopped and connected to the beacon
    // disconnect from the beacon
    if (stopped) {
      if (mGatt != null) {
        disconnectFromGatt();
      }
    } else if (actionType == TestAction.LAST) {
      Log.d(TAG, "Last");
      finished = true;
      mTestCallback.testCompleted(mBluetoothDevice, mGatt);
    } else if (actionType == TestAction.CONNECT) {
      Log.d(TAG, "Conenct");
      connectToGatt();
    } else if (actionType == TestAction.ADV_FLAGS) {
      Log.d(TAG, "ADV FLAGS");
      lookForAdv();
    } else if (actionType == TestAction.ADV_TX_POWER) {
      Log.d(TAG, "ADV TX POWER");
      lookForAdv();
    } else if (actionType == TestAction.ADV_URI) {
      Log.d(TAG, "ADV uri");
      lookForAdv();
    } else if (actionType == TestAction.ADV_PACKET) {
      Log.d(TAG, "ADV packet");
      lookForAdv();
    } else if (mGatt == null) {
      Log.d(TAG, "no gatt. conencting");
      connectToGatt();
    } else if (actionType == TestAction.ASSERT || actionType == TestAction.ASSERT_NOT_EQUALS) {
      Log.d(TAG, "Read");
      readFromGatt();
    } else if (actionType == TestAction.WRITE || actionType == TestAction.MULTIPLE_VALID_RETURN_CODES) {
      Log.d(TAG, "Write");
      writeToGatt();
    } else if (actionType == TestAction.DISCONNECT) {
      Log.d(TAG, "Disconenct");
      disconnectFromGatt();
    }
  }

  private void scanForBeacon() {
    ScanSettings settings = new ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build();
    List<ScanFilter> filters = new ArrayList<>();

    ScanFilter filter = new ScanFilter.Builder()
        .setServiceUuid(ProtocolV2.CONFIG_SERVICE_UUID)
        .build();
    filters.add(filter);
    getLeScanner().startScan(filters, settings, mScanCallback);
    Log.d(TAG, "Looking for new beacons");
    mHandler.postDelayed(new Runnable() {
      @Override
      public void run() {
        stopSearchingForBeacons();
        if (mScanResults.size() == 0) {
          fail("No UriBeacons in Config Mode found");
        } else if (mScanResults.size() == 1) {
          continueTest(0);
        } else {
          mTestCallback.multipleConfigModeBeacons(mScanResults);
        }
      }
    }, SCAN_TIMEOUT);
  }

  private void lookForAdv() {
    ScanSettings settings = new ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build();
    List<ScanFilter> filters = new ArrayList<>();

    ScanFilter filter = new ScanFilter.Builder()
        .setServiceUuid(UriBeacon.URI_SERVICE_UUID)
        .build();
    filters.add(filter);
    getLeScanner().startScan(filters, settings, mScanCallback);
    mHandler.postDelayed(new Runnable() {
      @Override
      public void run() {
        stopSearchingForBeacons();
        if (mBluetoothDevice != null) {
          for (ScanResult scanResult : mScanResults) {
            if (scanResult.getDevice().getAddress().equals(mBluetoothDevice.getAddress())) {
              checkPacket(scanResult);
              break;
            }
          }
        } else if (mScanResults.size() == 1) {
          continueTest(0);
        } else if (mScanResults.size() > 1) {
          mTestCallback.multipleConfigModeBeacons(mScanResults);
        } else {
          fail("Could not find adv packet");
        }
      }
    }, SCAN_TIMEOUT);
  }

  private void stopSearchingForBeacons() {
    getLeScanner().stopScan(mScanCallback);
  }

  private void checkPacket(ScanResult result) {
    mHandler.removeCallbacksAndMessages(null);
    stopSearchingForBeacons();
    Log.d(TAG, "Found beacon");
    TestAction action = mTestActions.peek();
    if (action.actionType == TestAction.ADV_PACKET) {
      if (getAdvPacket(result).length < 2) {
        fail("Invalid Adv Packet");
      } else {
        mTestActions.remove();
        dispatch();
      }
    } else if (action.actionType == TestAction.ADV_FLAGS) {
      byte flags = getFlags(result);
      byte expectedFlags = action.transmittedValue[0];
      if (expectedFlags != flags) {
        fail("Received: " + flags + ". Expected: " + expectedFlags);
      } else {
        mTestActions.remove();
        dispatch();
      }
    } else if (action.actionType == TestAction.ADV_TX_POWER) {
      byte txPowerLevel = getTxPowerLevel(result);
      byte expectedTxPowerLevel = action.transmittedValue[0];
      if (expectedTxPowerLevel != txPowerLevel) {
        fail("Received: " + txPowerLevel + ". Expected: " + expectedTxPowerLevel);
      } else {
        mTestActions.remove();
        dispatch();
      }
    } else if (action.actionType == TestAction.ADV_URI) {
      byte[] uri = getUri(result);
      if (!Arrays.equals(action.transmittedValue, uri)) {
        fail("Received: " + Arrays.toString(uri)
            + ". Expected: " + Arrays.toString(action.transmittedValue));
      } else {
        mTestActions.remove();
        dispatch();
      }
    }
  }

  private BluetoothLeScanner getLeScanner() {
    return mBluetoothAdapter.getBluetoothLeScanner();
  }

  private byte getFlags(ScanResult result) {
    byte[] serviceData = result.getScanRecord().getServiceData(UriBeacon.URI_SERVICE_UUID);
    return serviceData[0];
  }

  private byte getTxPowerLevel(ScanResult result) {
    byte[] serviceData = result.getScanRecord().getServiceData(UriBeacon.URI_SERVICE_UUID);
    return serviceData[1];
  }

  private byte[] getUri(ScanResult result) {
    byte[] serviceData = result.getScanRecord().getServiceData(UriBeacon.URI_SERVICE_UUID);
    return Arrays.copyOfRange(serviceData, 2, serviceData.length);
  }

  private byte[] getAdvPacket(ScanResult result) {
    return result.getScanRecord().getServiceData(UriBeacon.URI_SERVICE_UUID);
  }

  private void fail(String reason) {
    Log.d(TAG, "Failing because: " + reason);
    mHandler.removeCallbacksAndMessages(null);
    failed = true;
    mTestActions.peek().failed = true;
    mTestActions.peek().reason = reason;
    finished = true;
    mTestCallback.testCompleted(mBluetoothDevice, mGatt);
  }

  public boolean isFinished() {
    return finished;
  }

  public void stopTest() {
    stopped = true;
    stopSearchingForBeacons();
    fail("Stopped by user");
  }

  public void continueTest(int which) {
    mBluetoothDevice = mScanResults.get(which).getDevice();
    dispatch();
  }

  public void repeat(BluetoothGattCallback outSideGattCallback) {
    Log.d(TAG, "Repeating");
    mTestActions = new LinkedList<>(mTestSteps);
    run(mBluetoothDevice, null, outSideGattCallback);
  }

  public String getReference() {
    return mReference;
  }

  public interface TestCallback {

    public void testStarted();

    public void testCompleted(BluetoothDevice deviceBeingTested, BluetoothGatt gatt);

    public void waitingForConfigMode();

    public void connectedToBeacon();

    public void multipleConfigModeBeacons(ArrayList<ScanResult> scanResults);
  }

  public static class Builder {

    private String mName;
    private String mReference = "";
    private Context mContext;
    private UUID mServiceUuid;
    private TestCallback mTestCallback;
    private final LinkedList<TestAction> mTestActions = new LinkedList<>();

    public Builder name(String name) {
      mName = name;
      return this;
    }

    public Builder reference(String reference) {
      mReference = reference;
      return this;
    }

    public Builder setUp(Context context, ParcelUuid serviceUuid,
        TestCallback testCallback) {
      mContext = context;
      mServiceUuid = serviceUuid.getUuid();
      mTestCallback = testCallback;
      return this;
    }

    public Builder connect() {
      mTestActions.add(new TestAction(TestAction.CONNECT));
      return this;
    }

    public Builder write(UUID characteristicUuid, byte[] value, int expectedReturnCode) {
      mTestActions.add(
          new TestAction(TestAction.WRITE, characteristicUuid, expectedReturnCode, value));
      return this;
    }

    public Builder disconnect() {
      mTestActions.add(new TestAction(TestAction.DISCONNECT));
      return this;
    }

    public Builder assertEquals(UUID characteristicUuid, byte[] expectedValue,
        int expectedReturnCode) {
      mTestActions.add(
          new TestAction(TestAction.ASSERT, characteristicUuid, expectedReturnCode, expectedValue));
      return this;
    }

    public Builder write(UUID characteristicUuid, byte[] value,
        int[] expectedReturnCodes) {
      mTestActions.add(
          new TestAction(TestAction.MULTIPLE_VALID_RETURN_CODES, characteristicUuid, value, expectedReturnCodes)
      );
      return this;
    }

    public Builder assertNotEquals(UUID characteristicUuid, byte[] expectedValue,
        int expectedReturnCode) {
      mTestActions.add(
          new TestAction(TestAction.ASSERT_NOT_EQUALS, characteristicUuid, expectedReturnCode, expectedValue));
      return this;
    }

    public Builder assertAdvFlags(byte expectedValue) {

      mTestActions.add(new TestAction(TestAction.ADV_FLAGS, new byte[]{expectedValue}));
      return this;
    }

    public Builder assertAdvTxPower(byte expectedValue) {
      mTestActions.add(new TestAction(TestAction.ADV_TX_POWER, new byte[]{expectedValue}));
      return this;
    }

    public Builder assertAdvUri(byte[] expectedValue) {
      mTestActions.add(new TestAction(TestAction.ADV_URI, expectedValue));
      return this;
    }

    public Builder checkAdvPacket() {
      mTestActions.add(new TestAction(TestAction.ADV_PACKET));
      return this;
    }

    public Builder insertActions(Builder builder) {
      for (TestAction action : builder.mTestActions) {
        mTestActions.add(action);
      }
      return this;
    }

    public Builder writeAndRead(UUID characteristicUuid, byte[][] values) {
      for (byte[] value : values) {
        writeAndRead(characteristicUuid, value);
      }
      return this;
    }

    public Builder writeAndRead(UUID characteristicUuid, byte[] value) {
      mTestActions.add(
          new TestAction(TestAction.WRITE, characteristicUuid, BluetoothGatt.GATT_SUCCESS,
              value));
      mTestActions.add(
          new TestAction(TestAction.ASSERT, characteristicUuid, BluetoothGatt.GATT_SUCCESS,
              value));
      return this;
    }

    public TestHelper build() {
      mTestActions.add(new TestAction(TestAction.LAST));
      // Keep a copy of the steps to show in the UI
      LinkedList<TestAction> testSteps = new LinkedList<>(mTestActions);
      return new TestHelper(mName, mReference, mContext, mServiceUuid, mTestCallback,
          mTestActions, testSteps);
    }
  }
}
