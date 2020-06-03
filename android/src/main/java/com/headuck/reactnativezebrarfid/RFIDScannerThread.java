package com.headuck.reactnativezebrarfid;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.lang.NullPointerException;

import com.zebra.rfid.api3.*;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import org.apache.commons.lang3.StringUtils;

public abstract class RFIDScannerThread extends Thread implements RfidEventsListener {

    // Mode keys
    private final static String NONE = "none";
    private final static String INVENTORY = "inventory";
    private final static String READ = "read";
    private final static String WRITE = "write";
    private final static String LOCK = "lock";

    // Config keys
    private final static String MEMORY = "memory_bank";
    private final static String TAG_ID = "tagId";
    private final static String TAG_DATA = "tagData";
    private final static String FILTER_MEMORY = "filter_memory_bank";
    private final static String READ_LENGTH = "read_length";
    private final static String LOCK_MEMORY = "lock_memory";
    private final static String LOCK_PASSWORD = "lock_password";

    private final static String LOCK_ACCESS_PASSWORD = "lock_password";
    private final static String LOCK_USER_MEMORY = "lock_user_memory";

    private ReactApplicationContext context;

    private Readers readers = null;
    private ArrayList<ReaderDevice> deviceList = null;
    private ReaderDevice rfidReaderDevice = null;
    boolean tempDisconnected = false;

    private String rfidMode = NONE;

    // Config set by setMode
    private String tagId = null;
    private String tagData = null;
    private MEMORY_BANK memory_bank = null;
    private MEMORY_BANK filter_memory_bank = null;
    private int readLength = 0;
    private int readLengthOffset = 0;
    private int writeDataOffset = 0;
    private LOCK_DATA_FIELD lock_memory = null;
    private long lock_password = 0;

    private Boolean active = false;
    private ReadableMap config = null;
    private Boolean deferTriggerReleased = false;

    public RFIDScannerThread(ReactApplicationContext context) {
        this.context = context;
    }

    public void run() {}

    public abstract void dispatchEvent(String name, WritableMap data);
    public abstract void dispatchEvent(String name, String data);
    public abstract void dispatchEvent(String name, WritableArray data);

    // -------
    // Helpers
    // -------
    private RFIDReader getConnectedRFIDReader() throws Exception {
        if (rfidReaderDevice != null) {
            if (rfidReaderDevice.getRFIDReader().isConnected()) {
                return rfidReaderDevice.getRFIDReader();
            } else {
                log("cancel: device not connected");
                throw new Exception("cancel: device not connected");
            }
        } else {
            log("cancel: device not initialised");
            throw new Exception("cancel: device not initialised");
        }
    }

    private void resetModeData() {
        tagId = null;
        tagData = null;
        memory_bank = null;
        filter_memory_bank = null;
        readLength = 0;
        readLengthOffset = 0;
        writeDataOffset = 0;
    }
    private void LogEvent(String message) {
        WritableMap event = Arguments.createMap();
        event.putString("RFIDStatusEvent", "LOG: " + message);
        this.dispatchEvent("RFIDStatusEvent", event);
    }
    private void log(String message) {
        Log.i("TEST123", message);
    }

    // ----------
    // Life Cycle
    // ----------

    public void init(Context context) {
        // Register receiver
        readers = new Readers(context, ENUM_TRANSPORT.BLUETOOTH);
        try {
            ArrayList<ReaderDevice> availableRFIDReaderList = readers.GetAvailableRFIDReaderList();
            deviceList = availableRFIDReaderList;
            log("RFID - Scanner thread initialized");
        } catch (InvalidUsageException e) {
            log("RFID - Init scanner error - invalid message: " + e.getMessage());
        } catch (NullPointerException ex) {
            log("RFID - Blue tooth not support on device");
        }

        tempDisconnected = false;
        active = false;
        rfidMode = NONE;
        tagId = null;
        this.connect();
    }

    private void connect() {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (rfidReaderDevice.getRFIDReader().isConnected()) return;
            disconnect();
        }
        try {
            ArrayList<ReaderDevice> availableRFIDReaderList = null;
            try {
                readers = new Readers(this.context, ENUM_TRANSPORT.SERVICE_SERIAL);
                availableRFIDReaderList = readers.GetAvailableRFIDReaderList();
                deviceList = availableRFIDReaderList;
            } catch (InvalidUsageException e) {
                log("connect RFID - Init scanner error - invalid message: " + e.getMessage());
            } catch (NullPointerException ex) {
                log("RFID - Blue tooth not support on device");
            }

            int listSize = (availableRFIDReaderList == null) ? 0 : availableRFIDReaderList.size();
            if (listSize > 0) {
                ReaderDevice readerDevice = availableRFIDReaderList.get(0);
                RFIDReader rfidReader = readerDevice.getRFIDReader();
                // Connect to RFID reader
                if (rfidReader != null) {
                    while (true) {
                        try {
                            rfidReader.connect();

//                            rfidReader.Actions.reset();
//                            RegulatoryConfig regulatoryConfig = rfidReader.Config.getRegulatoryConfig();
//                            rfidReader.Config.resetFactoryDefaults();
//                            rfidReader.Config.setRegulatoryConfig(regulatoryConfig);

                            rfidReader.Config.getDeviceStatus(true, false, false);
                            rfidReader.Events.addEventsListener(this);
                            // Subscribe required status notification
                            rfidReader.Events.setInventoryStartEvent(true);
                            rfidReader.Events.setInventoryStopEvent(true);

                            rfidReader.Events.setAttachTagDataWithReadEvent(false);
                            // enables tag read notification
                            rfidReader.Events.setTagReadEvent(true);
                            rfidReader.Events.setReaderDisconnectEvent(true);
                            rfidReader.Events.setBatteryEvent(true);
                            rfidReader.Events.setBatchModeEvent(true);
                            rfidReader.Events.setHandheldEvent(true);
                            // Set trigger mode
                            setTriggerMode(rfidReader);
                            break;
                        } catch (OperationFailureException ex) {
                            if (ex.getResults() == RFIDResults.RFID_READER_REGION_NOT_CONFIGURED) {
                                // Get and Set regulatory configuration settings
                                try {
                                    RegulatoryConfig regulatoryConfig = rfidReader.Config.getRegulatoryConfig();
                                    SupportedRegions regions = rfidReader.ReaderCapabilities.SupportedRegions;
                                    int len = regions.length();
                                    boolean regionSet = false;
                                    for (int i = 0; i < len; i++) {
                                        RegionInfo regionInfo = regions.getRegionInfo(i);
                                        if ("HKG".equals(regionInfo.getRegionCode())) {
                                            regulatoryConfig.setRegion(regionInfo.getRegionCode());
                                            rfidReader.Config.setRegulatoryConfig(regulatoryConfig);
                                            Log.i("RFID", "Region set to " + regionInfo.getName());
                                            regionSet = true;
                                            break;
                                        }
                                    }
                                    if (!regionSet) {
                                        err = "Region not found";
                                        break;
                                    }
                                } catch (OperationFailureException ex1) {
                                    err = "Error setting RFID region: " + ex1.getMessage();
                                    break;
                                }
                            } else if (ex.getResults() == RFIDResults.RFID_CONNECTION_PASSWORD_ERROR) {
                                // Password error
                                err = "Password error";
                                break;
                            } else if (ex.getResults() == RFIDResults.RFID_BATCHMODE_IN_PROGRESS) {
                                // handle batch mode related stuff
                                err = "Batch mode in progress";
                                break;
                            } else {
                                err = ex.getResults().toString();
                                break;
                            }
                        } catch (InvalidUsageException e1) {
                            Log.e("RFID", "InvalidUsageException: " + e1.getMessage() + " " + e1.getInfo());
                            err = "Invalid usage " + e1.getMessage();
                            break;
                        } catch (Exception exc) {
                            err = exc.getMessage();
                        }
                    }
                } else {
                    err = "Cannot get rfid reader";
                }
                if (err == null) {
                    // Connect success
                    rfidReaderDevice = readerDevice;
                    tempDisconnected = false;
                    WritableMap event = Arguments.createMap();
                    event.putString("RFIDStatusEvent", "opened");
                    this.dispatchEvent("RFIDStatusEvent", event);
                    Log.i("RFID", "Connected to " + rfidReaderDevice.getName());
                    return;
                }
            } else {
                err = "No connected device";
            }
        } catch (InvalidUsageException e) {
            err = "connect: invalid usage error: " + e.getMessage();
        }
        if (err != null) {
            log("Connect RFID - " + err);
        }
    }

    public void reconnect() {
        if (rfidReaderDevice != null) {
            if (tempDisconnected) {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                if (!rfidReader.isConnected()) {
                    String err = null;
                    try {
                        // Stop inventory
                        rfidReader.reconnect();
                    } catch (InvalidUsageException e) {
                        err = "reconnect: invalid usage error: " + e.getMessage();
                    } catch (OperationFailureException ex) {
                        err = "reconnect error: " + ex.getResults().toString();
                    }
                    if (err != null) {
                        Log.e("RFID", err);
                    } else {
                        tempDisconnected = false;
                        WritableMap event = Arguments.createMap();
                        event.putString("RFIDStatusEvent", "opened");
                        this.dispatchEvent("RFIDStatusEvent", event);
                        Log.i("RFID", "Reconnected to " + rfidReaderDevice.getName());
                    }
                } else {
                    Log.i("RFID", rfidReaderDevice.getName() + " is already connected");
                }
            } else {
                Log.i("RFID", "reconnect: not temp disconnected");
            }
        } else {
            Log.i("RFID", "reconnect: device is null");
        }
    }

    private void disconnect() {
        if (this.rfidReaderDevice != null){
            RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
            String err = null;
            if (!rfidReader.isConnected()) {
                Log.i("RFID", "disconnect: already disconnected");
                // already disconnected
            } else {
                try {
                    rfidReader.disconnect();
                } catch (InvalidUsageException e) {
                    err = "disconnect: invalid usage error: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "disconnect: " + ex.getResults().toString();
                }
            }
            try {
                if (rfidReader.Events != null) {
                    rfidReader.Events.removeEventsListener(this);
                }
            } catch (InvalidUsageException e) {
                err = "disconnect: invalid usage error when removing events: " + e.getMessage();
            } catch (OperationFailureException ex) {
                err = "disconnect: error removing events: " + ex.getResults().toString();
            }
            if (err != null) {
                log("RFID - " + err);
            }
            // Ignore error and send feedback
            WritableMap event = Arguments.createMap();
            event.putString("RFIDStatusEvent", "closed");
            this.dispatchEvent("RFIDStatusEvent", event);
            rfidReaderDevice = null;
            tempDisconnected = false;
        } else {
            Log.w("RFID", "disconnect: no device was connected");
        }

    }

    public void shutdown() {
        if (this.rfidReaderDevice != null) {
            disconnect();
        }
        // Unregister receiver
        if (readers != null) {
            readers.Dispose();
            readers = null;
        }
        deviceList = null;
    }
    // ----------
    // App Events
    // ----------

    public void onHostResume() {
        if (readers != null) {
            this.connect();
        } else {
             Log.e("RFID", "Can't resume - reader is null");
        }
    }

    public void onHostPause() {
        this.cancel();
        this.disconnect();
    }

    public void onHostDestroy() {
        this.cancel();
        shutdown();
    }

    public void onCatalystInstanceDestroy() {
        this.cancel();
        shutdown();
    }

    // ------------------
    // General Operations
    // ------------------
    public void setMode(String mode, ReadableMap config) {
        LogEvent("SET MODE: " + mode);
        if (active) {
            cancel();
        }
        String err = null;
        resetModeData();
//        try {
//            RFIDReader rfidReader = getConnectedRFIDReader();
//            switch (mode) {
//                case READ:
//
//                    break;
//                case WRITE:
//                    if (config.hasKey(TAG_ID)) {
//                        this.tagId = config.getString(TAG_ID);
//                    }
//                    if (config.hasKey(TAG_ID)) {
//                        this.tagId = config.getString(TAG_DATA);
//                    }
//                    break;
//            }
            String memory_bank_string;
            String filter_memory_bank_string;
            if (config.hasKey(MEMORY)) {
                memory_bank_string = config.getString(MEMORY);
            } else {
                memory_bank_string = "user";
            }
            if (config.hasKey(FILTER_MEMORY)) {
                filter_memory_bank_string = config.getString(FILTER_MEMORY);
            } else {
                filter_memory_bank_string = "user";
            }

            try {
                memory_bank = MEMORY_BANK.GetMemoryBankValue(memory_bank_string);
            } catch(Exception e) {
                log("ERROR1" + e.getMessage());
            }
            try {
                filter_memory_bank = MEMORY_BANK.GetMemoryBankValue(filter_memory_bank_string);
            } catch(Exception e) {
                log("ERROR2" + e.getMessage());
            }

            if (config.hasKey(TAG_ID)) {
                this.tagId = config.getString(TAG_ID);
            }
            if (config.hasKey(TAG_DATA)) {
                this.tagData = config.getString(TAG_DATA);
                int writeLengthRest = tagData.length() % 4;
                if (writeLengthRest > 0) {
                    this.writeDataOffset = 4 - writeLengthRest;
                    this.tagData += StringUtils.repeat("0",writeDataOffset);
                    log("WRITE LENGTH" + " - " + tagData.length());
                }
            }
            if (config.hasKey(READ_LENGTH)) {
                this.readLength = config.getInt(READ_LENGTH) / 4;
                int readLengthRest = config.getInt(READ_LENGTH) % 4;
                if (readLengthRest > 0) {
                    this.readLength += 1;
                    this.readLengthOffset = 4 - readLengthRest;
                    log(readLength + " - " + readLengthOffset);
                }
            }

            if (config.hasKey(LOCK_MEMORY)) {
                switch (config.getString(TAG_DATA)) {
                    case LOCK_ACCESS_PASSWORD:
                        lock_memory = LOCK_DATA_FIELD.LOCK_ACCESS_PASSWORD;
                        break;
                    case LOCK_USER_MEMORY:
                        lock_memory = LOCK_DATA_FIELD.LOCK_USER_MEMORY;
                        break;
                }
            }

            this.rfidMode = mode;
            log("MODE: " + mode + " " + this.tagData + " " + this.tagId);

//        } catch (InvalidUsageException e) {
//            err = "cancel: invalid usage error on scanner read: " + e.getMessage();
//        } catch (OperationFailureException ex) {
//            err = "cancel: error setting up scanner read: " + ex.getResults().toString();
//        } catch(Exception exc) {
//            err = exc.getMessage();
//        }

        if (err != null) {
            log("RFID setMode - " + err);
        }
    }

    public void cancel() {
        String err = null;
        try {
            switch (rfidMode) {
                case INVENTORY:
                    // Stop inventory
                    stopInventory();
                    break;
                case READ:
                    log("CANCEL READ");
                    stopRead();
                    break;
                case WRITE:
                    log("CANCEL WRITE");
                    stopWrite();
                    break;
                case LOCK:
                    stopLock();
                    break;
                case NONE:
                    // Do nothing
                    break;
            }
        } catch (InvalidUsageException e) {
            err = "cancel: invalid usage error on scanner read: " + e.getMessage();
        } catch (OperationFailureException ex) {
            err = "cancel: error setting up scanner read: " + ex.getResults().toString();
        } catch(Exception exc) {
            err = exc.getMessage();
        }

        if (err != null) {
            log("RFID - " + err);
        }
    }

    // ---------
    // Inventory
    // ---------
    public void startInventory(ReadableMap config) throws Exception {
        if (!active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.Inventory.perform(null, null, null);
            active = true;
        }
    }

    public void stopInventory () throws Exception {
        if (active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.Inventory.stop();
            active = false;
        }
    }
    // -------
    // Writing
    // -------
    public void write() throws Exception {
        log("WRITE");
        if (!active) {
            RFIDReader rfidReader = getConnectedRFIDReader();

            setWriteConfig(rfidReader);

            TagAccess tagAccess = new TagAccess();
            TagAccess.WriteAccessParams writeAccessParams = tagAccess.new WriteAccessParams();

            writeAccessParams.setAccessPassword(Long.parseLong("0",16));
            if (memory_bank != null) {
                writeAccessParams.setMemoryBank(memory_bank);
            } else {
                writeAccessParams.setMemoryBank(MEMORY_BANK.MEMORY_BANK_USER);
            }
            writeAccessParams.setOffset(0); // start writing from word offset 0
            writeAccessParams.setWriteData(tagData);
            // set retries in case of partial write happens
            writeAccessParams.setWriteRetries(3);
            // data length in words
            writeAccessParams.setWriteDataLength(tagData.length() / 4);
            // 5th parameter bPrefilter flag is true which means API will apply pre filter internally
            // 6th parameter should be true in case of changing EPC ID it self i.e. source and target both is EPC
//            boolean useTIDfilter = memory_bank == MEMORY_BANK.MEMORY_BANK_EPC;

            AccessFilter accessFilter = null;
            // Tag Pattern A
            accessFilter = new AccessFilter();
            if (filter_memory_bank != null) {
                accessFilter.TagPatternA.setMemoryBank(filter_memory_bank);
            } else {
                accessFilter.TagPatternA.setMemoryBank(MEMORY_BANK.MEMORY_BANK_USER);
            }

            accessFilter.TagPatternA.setTagPattern(tagId);
            accessFilter.TagPatternA.setTagPatternBitCount(tagId.length() * 4);
            accessFilter.TagPatternA.setBitOffset(0);
            accessFilter.TagPatternA.setTagMask(tagId);
            accessFilter.TagPatternA.setTagMaskBitCount(tagId.length() * 4);
            accessFilter.setAccessFilterMatchPattern(FILTER_MATCH_PATTERN.A);

            // perform write
            active = true;
            rfidReader.Actions.TagAccess.writeEvent(writeAccessParams, accessFilter, null);
        }
    }

    public void stopWrite () throws Exception {
        if (active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.TagAccess.stopAccess();
            active = false;
        }
    }
    // -------
    // Reading
    // -------
    public void read () throws Exception {
        if (!active) {
            RFIDReader rfidReader = getConnectedRFIDReader();

            TagAccess tagAccess = new TagAccess();
            TagAccess.ReadAccessParams readAccessParams = tagAccess.new ReadAccessParams();

            readAccessParams.setAccessPassword(0);
            // read n words
            readAccessParams.setCount(readLength);
            // user memory bank
            if (memory_bank != null) {
                readAccessParams.setMemoryBank(memory_bank);
            } else {
                readAccessParams.setMemoryBank(MEMORY_BANK.MEMORY_BANK_USER);
            }
            // start reading from word offset 0
            readAccessParams.setOffset(0);

            AccessFilter accessFilter = null;
            if (tagId != null && filter_memory_bank != null) {
                log("ACCESS FILTER " + tagId + " " + tagId.length());
                // Tag Pattern A
                accessFilter = new AccessFilter();
                accessFilter.TagPatternA.setMemoryBank(filter_memory_bank);
                accessFilter.TagPatternA.setTagPattern(tagId);
                accessFilter.TagPatternA.setTagPatternBitCount(tagId.length() * 4);
                accessFilter.TagPatternA.setBitOffset(0);
                accessFilter.TagPatternA.setTagMask(tagId);
                accessFilter.TagPatternA.setTagMaskBitCount(tagId.length() * 4);
                accessFilter.setAccessFilterMatchPattern(FILTER_MATCH_PATTERN.A);
            }

            // read operation
            rfidReader.Actions.TagAccess.readEvent(readAccessParams, accessFilter, null);
            active = true;
        }

    }
    public void stopRead () throws Exception {
        if (active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.TagAccess.stopAccess();
            active = false;
        }
    }
    // ----
    // LOCK
    // ----
    public void lock () throws Exception {
        if (!active) {
            RFIDReader rfidReader = getConnectedRFIDReader();

            // Lock the tag
            TagAccess tagAccess = new TagAccess();
            TagAccess.LockAccessParams lockAccessParams = tagAccess.new LockAccessParams();
            /* lock now */
            lockAccessParams.setLockPrivilege(lock_memory, LOCK_PRIVILEGE.LOCK_PRIVILEGE_READ_WRITE);
            lockAccessParams.setAccessPassword(lock_password);

            AccessFilter accessFilter = null;
            if (tagId != null && filter_memory_bank != null) {
                log("ACCESS FILTER " + tagId + " " + tagId.length());
                // Tag Pattern A
                accessFilter = new AccessFilter();
                accessFilter.TagPatternA.setMemoryBank(filter_memory_bank);
                accessFilter.TagPatternA.setTagPattern(tagId);
                accessFilter.TagPatternA.setTagPatternBitCount(tagId.length() * 4);
                accessFilter.TagPatternA.setBitOffset(0);
                accessFilter.TagPatternA.setTagMask(tagId);
                accessFilter.TagPatternA.setTagMaskBitCount(tagId.length() * 4);
                accessFilter.setAccessFilterMatchPattern(FILTER_MATCH_PATTERN.A);
            }

            active = true;
            rfidReader.Actions.TagAccess.lockEvent(lockAccessParams, accessFilter, null);
        }
    }

    public void stopLock () throws Exception {
        if (active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.TagAccess.stopAccess();
            active = false;
        }
    }
    // -------------
    // Configuration
    // -------------
    private void setTriggerMode(RFIDReader reader) throws Exception {
        TriggerInfo triggerInfo = new TriggerInfo();
        // Start trigger: set to immediate mode
        triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        // Stop trigger: set to immediate mode
        triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
        reader.Config.setStartTrigger(triggerInfo.StartTrigger);
        reader.Config.setStopTrigger(triggerInfo.StopTrigger);
    }

    private void setAntennaPower(RFIDReader reader, int power) throws InvalidUsageException, OperationFailureException {
        Antennas.AntennaRfConfig config = reader.Config.Antennas.getAntennaRfConfig(1);
        config.setTransmitPowerIndex(power);
        config.setrfModeTableIndex(0); // antennaRfConfig.setrfModeTableIndex(4);
        config.setTari(0);
        reader.Config.Antennas.setAntennaRfConfig(1, config);
        reader.Config.saveConfig();

        WritableMap event = Arguments.createMap();
        event.putString("SettingsEvent", "Setting Antennas to " + power + " completed");
        this.dispatchEvent("SettingEvent", event);
    }

    private void setDPO(RFIDReader reader, boolean bEnable) throws InvalidUsageException, OperationFailureException {
       reader.Config.setDPOState(bEnable ? DYNAMIC_POWER_OPTIMIZATION.ENABLE : DYNAMIC_POWER_OPTIMIZATION.DISABLE);
    }

    private void setWriteConfig(RFIDReader reader) throws InvalidUsageException, OperationFailureException {
        // set required power and profile
        setAntennaPower(reader, 240);
        // in case of RFD8500 disable DPO
        if (reader.getHostName().contains("RFD8500")) {
            setDPO(reader,false);
        }
        // set access operation time out value to 1 second, so reader will tries for a second
        // to perform operation before timing out
        reader.Config.setAccessOperationWaitTimeout(1000);
    }

    // -------------------
    // RfidEventsListeners
    // -------------------
    @Override
    public void eventReadNotify(RfidReadEvents rfidReadEvents) {
        log("ACTIVE: " + active);
        if (active) {
            String err = null;
            try {
                RFIDReader rfidReader = getConnectedRFIDReader();
                TagDataArray tagArray = rfidReader.Actions.getReadTagsEx(100);
                if (tagArray != null) {
                    WritableArray rfidTags = Arguments.createArray();
                    for (int i = 0; i < tagArray.getLength(); i++) {
                        TagData tag = tagArray.getTags()[i];
                        String tagResultData = null;
                        switch (rfidMode) {
                            case NONE:
                                break;
                            case INVENTORY:
                                tagResultData = tag.getTagID();
                                break;
                            case WRITE:
                                if (tag != null) {
                                    ACCESS_OPERATION_CODE readAccessOperation = tag.getOpCode();
                                    if (readAccessOperation != null) {
                                        if (tag.getOpStatus() != null && !tag.getOpStatus().equals(ACCESS_OPERATION_STATUS.ACCESS_SUCCESS)) {
                                            err = tag.getOpStatus().toString().replaceAll("_", " ");
                                            tagResultData = err;
                                        } else {
                                            if (tag.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_WRITE) {
                                                tagResultData = "WRITE SUCCESS";
                                            } else {
                                                err = "WRITE FAIL";
                                                tagResultData = err;
                                            }
                                        }
                                    } else {
                                        err = "ACCESS WRITE memoryBankData is null";
                                        tagResultData = err;
                                    }
                                } else {
                                    err = "ACCESS WRITE OPERATION FAILED";
                                    tagResultData = err;
                                }
                                break;
                            case READ:
                                if (tag != null) {
                                    ACCESS_OPERATION_CODE readAccessOperation = tag.getOpCode();
                                    if (readAccessOperation != null) {
                                        if (tag.getOpStatus() != null && !tag.getOpStatus().equals(ACCESS_OPERATION_STATUS.ACCESS_SUCCESS)) {
                                            err = tag.getOpStatus().toString().replaceAll("_", " ");
                                            tagResultData = err;
                                        } else {
                                            if (tag.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ) {
                                                tagResultData = tag.getMemoryBankData();
                                                if (readLengthOffset > 0) {
                                                    tagResultData = tagResultData.substring(0, tagResultData.length() - readLengthOffset);
                                                }
                                                log("READ DATA: " + tagResultData + " ID: " + tag.getTagID() + " TIME: " + tag.SeenTime.getUTCTime().toString());
                                            } else {
                                                err = "READ FAIL";
                                                tagResultData = err;
                                            }
                                        }
                                    } else {
                                        err = "ACCESS READ memoryBankData is null";
                                        tagResultData = err;
                                    }
                                } else {
                                    err = "ACCESS OPERATION FAILED";
                                    tagResultData = err;
                                }
                                break;
                            case LOCK:
                                if (tag != null) {
                                    ACCESS_OPERATION_CODE readAccessOperation = tag.getOpCode();
                                    if (readAccessOperation != null) {
                                        if (tag.getOpStatus() != null && !tag.getOpStatus().equals(ACCESS_OPERATION_STATUS.ACCESS_SUCCESS)) {
                                            err = tag.getOpStatus().toString().replaceAll("_", " ");
                                            tagResultData = err;
                                        } else {
                                            if (tag.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_LOCK) {
                                                tagResultData = tag.getMemoryBankData();
                                                if (readLengthOffset > 0) {
                                                    tagResultData = tagResultData.substring(0, tagResultData.length() - readLengthOffset);
                                                }
                                                log("DATA: " + tagResultData + " ID: " + tag.getTagID() + " TIME: " + tag.SeenTime.getUTCTime().toString());
                                            } else {
                                                err = "LOCK FAIL";
                                                tagResultData = err;
                                            }
                                        }
                                    } else {
                                        err = "ACCESS LOCK memoryBankData is null";
                                        tagResultData = err;
                                    }
                                } else {
                                    err = "ACCESS LOCK OPERATION FAILED";
                                    tagResultData = err;
                                }
                                break;
                        }
                        if (tagResultData != null) {
                            this.dispatchEvent("TagEvent", tagResultData);
                            rfidTags.pushString(tagResultData);
                        }
                    }
                    if (rfidTags.size() > 0) {
                        this.dispatchEvent("TagsEvent", rfidTags);
                    }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }

            if (err != null) {
                log("RFID - " + err);
            }
        }
    }

    @Override
    public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
        WritableMap event = Arguments.createMap();
        STATUS_EVENT_TYPE statusEventType = rfidStatusEvents.StatusEventData.getStatusEventType();
        log(statusEventType.toString());

        if (statusEventType == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
            event.putString("RFIDStatusEvent", "inventoryStart");
        } else if (statusEventType == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
            event.putString("RFIDStatusEvent", "inventoryStop");
        } else if (statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
            event.putString("RFIDStatusEvent", "disconnect");
            tempDisconnected = true;
        } else if (statusEventType == STATUS_EVENT_TYPE.BATCH_MODE_EVENT) {
            event.putString("RFIDStatusEvent", "batchMode" + rfidStatusEvents.StatusEventData.BatchModeEventData.toString());
        } else if (statusEventType == STATUS_EVENT_TYPE.BATTERY_EVENT) {
            event.putString("RFIDStatusEvent", "battery " + rfidStatusEvents.StatusEventData.BatteryData.getLevel());
        } else if (statusEventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
            HANDHELD_TRIGGER_EVENT_TYPE eventData = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
            String err = null;

            if (eventData == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                try {
                    switch (rfidMode) {
                        case INVENTORY:
                            this.startInventory(this.config);
                            break;
                        case READ:
                            this.read();
                            break;
                        case WRITE:
                            log("TEST");
                            event.putString("RFIDStatusEvent", "inventoryStart");
                            this.write();
                            break;
                        case LOCK:
                            this.lock();
                            break;
                        case NONE:
                            tagId = null;
                            break;
                    }
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                } catch (Exception exc) {
                    err = exc.getMessage();
                }

                if (err != null) {
                    Log.e("RFID", err);
                }
            } else if (eventData == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                log("RELEASE: " + rfidMode + " " + active);
                if (rfidMode.equals(WRITE)) {
                    event.putString("RFIDStatusEvent", "inventoryStop");
                }
                this.cancel();
            }
        }

        // TODO: Richtig Implementieren
        if (event.hasKey("RFIDStatusEvent")) {
        //    if (!active) {
            this.dispatchEvent("RFIDStatusEvent", event);
        }
        //    } else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
        //        deferTriggerReleased = true;
        //    }
//       }
    }
}