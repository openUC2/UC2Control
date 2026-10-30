package com.openuc2.control;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Device detection for openUC2 boards.
 *
 * usb-serial-for-android 3.7.0 misses two chips that matter to us:
 *
 *   - Espressif native USB (VID 0x303A) on ESP32-S2/S3/C3. There is no entry
 *     for it; detection relies entirely on CdcAcmSerialDriver's descriptor
 *     walk, which fails on boards whose descriptors are unusual.
 *   - WCH CH9102F / CH343 (0x1A86:0x55Dx), now standard on ESP32-S3 boards.
 *     The library knows only CH340 (0x7523) and CH341 (0x5523), so these
 *     boards are not detected at all.
 *
 * Detection order in {@link #probe} is deliberate:
 *
 *   1. the library probe table, including its CDC descriptor walk;
 *   2. our extra VID/PID entries for chips it does not list;
 *   3. a last-resort guess for WCH devices.
 *
 * Step 3 is a guess on purpose. CH9102 parts ship in either a CDC or a
 * vendor-specific mode, and only some of them speak the CH340 protocol closely
 * enough for Ch34xSerialDriver. Putting the guess last means a CH9102 in CDC
 * mode is still driven correctly by step 1 — hard-coding 0x55D4 into the probe
 * table would have shadowed that, because the table is consulted before the
 * descriptor walk. When the guess is wrong, Force driver is the way out.
 */
public final class UsbDrivers {

    public static final int VENDOR_ESPRESSIF = 0x303A;
    public static final int VENDOR_WCH       = 0x1A86;
    public static final int VENDOR_SILABS    = 0x10C4;
    public static final int VENDOR_FTDI      = 0x0403;
    public static final int VENDOR_PROLIFIC  = 0x067B;

    /** Driver choices offered in the UI; index 0 = automatic detection. */
    public static final String[] DRIVER_NAMES =
            {"Auto-detect", "CDC-ACM", "CH34x / CH9102", "CP210x", "FTDI", "Prolific"};

    private static final Class<?>[] DRIVER_CLASSES = {
            null,
            CdcAcmSerialDriver.class,
            Ch34xSerialDriver.class,
            Cp21xxSerialDriver.class,
            FtdiSerialDriver.class,
            ProlificSerialDriver.class
    };

    private UsbDrivers() {}

    /** Library defaults plus the chips it does not list. */
    public static UsbSerialProber prober() {
        ProbeTable table = UsbSerialProber.getDefaultProbeTable();

        // Espressif native USB-Serial/JTAG and TinyUSB CDC. These really are
        // CDC devices, so an explicit entry only makes detection deterministic
        // — it never overrides a better match.
        for (int pid : new int[]{0x0002, 0x1001, 0x1000, 0x4001, 0x4002, 0x0009, 0x8000}) {
            table.addProduct(VENDOR_ESPRESSIF, pid, CdcAcmSerialDriver.class);
        }

        // CP2102N variants seen on newer UC2 boards. Not CDC devices, so no
        // descriptor walk can claim them first.
        for (int pid : new int[]{0xEA61, 0xEA63, 0xEA7A, 0xEA7B}) {
            table.addProduct(VENDOR_SILABS, pid, Cp21xxSerialDriver.class);
        }

        return new UsbSerialProber(table);
    }

    /**
     * Find a driver for one device: probe table first, WCH guess last.
     *
     * @return a driver, or {@code null} if nothing plausible fits
     */
    public static UsbSerialDriver probe(UsbDevice device) {
        UsbSerialDriver driver = prober().probeDevice(device);
        if (driver != null) return driver;

        // Unknown WCH part (CH343, CH9102, CH9101…). Worth one attempt with
        // the CH34x driver — it is that or nothing.
        if (device.getVendorId() == VENDOR_WCH) {
            return construct(Ch34xSerialDriver.class, device);
        }
        return null;
    }

    /** True when {@link #probe} only matched via the last-resort WCH guess. */
    public static boolean isGuess(UsbDevice device) {
        return device.getVendorId() == VENDOR_WCH && prober().probeDevice(device) == null;
    }

    /** All attached devices we can drive, most-likely-an-ESP32 first. */
    public static List<UsbSerialDriver> findDrivers(UsbManager manager) {
        List<UsbSerialDriver> espressif = new ArrayList<>();
        List<UsbSerialDriver> others = new ArrayList<>();

        for (UsbDevice device : manager.getDeviceList().values()) {
            UsbSerialDriver driver = probe(device);
            if (driver == null) continue;
            // Espressif-native boards are the common case; float them to the top
            // so Connect does the right thing when a hub or dock is also plugged in.
            if (device.getVendorId() == VENDOR_ESPRESSIF) espressif.add(driver);
            else others.add(driver);
        }
        espressif.addAll(others);
        return espressif;
    }

    /**
     * Build a driver using an explicitly chosen class, bypassing detection.
     * This is the escape hatch for a board nobody has seen before.
     *
     * @param driverIndex index into {@link #DRIVER_NAMES}; 0 means auto
     * @return the driver, or {@code null} if it could not be constructed
     */
    public static UsbSerialDriver forceDriver(UsbDevice device, int driverIndex) {
        if (driverIndex <= 0 || driverIndex >= DRIVER_CLASSES.length) return null;
        return construct(DRIVER_CLASSES[driverIndex], device);
    }

    @SuppressWarnings("unchecked")
    private static UsbSerialDriver construct(Class<?> driverClass, UsbDevice device) {
        try {
            Constructor<? extends UsbSerialDriver> ctor =
                    ((Class<? extends UsbSerialDriver>) driverClass)
                            .getConstructor(UsbDevice.class);
            return ctor.newInstance(device);
        } catch (Exception e) {
            return null;
        }
    }

    /** True for a camera: UVC (video class) or USB3 Vision (misc class, subclass 5). */
    public static boolean isCamera(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_MISC && itf.getInterfaceSubclass() == 5) return true;
        }
        return false;
    }

    /** Short label for a device, e.g. "ESP32-S3 (303A:1001)". */
    public static String label(UsbDevice device) {
        String name = null;
        try {
            name = device.getProductName();   // needs API 21+, null on some ROMs
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = vendorName(device.getVendorId());
        return String.format(Locale.US, "%s (%04X:%04X)",
                name, device.getVendorId(), device.getProductId());
    }

    public static String vendorName(int vid) {
        switch (vid) {
            case VENDOR_ESPRESSIF: return "Espressif";
            case VENDOR_WCH:       return "WCH";
            case VENDOR_SILABS:    return "Silicon Labs";
            case VENDOR_FTDI:      return "FTDI";
            case VENDOR_PROLIFIC:  return "Prolific";
            default:               return String.format(Locale.US, "VID 0x%04X", vid);
        }
    }

    /**
     * Full dump of every attached USB device and whether we can drive it.
     * This is the first thing to look at when a board "doesn't connect".
     */
    public static String describeAttachedDevices(UsbManager manager) {
        if (manager == null) return "USB host service unavailable — this phone has no OTG support.\n";

        StringBuilder sb = new StringBuilder();
        Collection<UsbDevice> devices = manager.getDeviceList().values();

        if (devices.isEmpty()) {
            return "No USB devices attached.\n\n"
                    + "Checklist:\n"
                    + "  1. Is the phone in USB host / OTG mode? Most phones need an\n"
                    + "     OTG adapter, not a plain C-to-C cable.\n"
                    + "  2. Is it a data cable? Charge-only cables enumerate nothing.\n"
                    + "  3. Is the board powered and not held in download mode?\n";
        }

        sb.append(devices.size()).append(" USB device(s) attached:\n");
        for (UsbDevice d : devices) {
            UsbSerialDriver driver = probe(d);
            boolean guessed = driver != null && isGuess(d);

            sb.append("\n• ").append(label(d)).append('\n');
            sb.append("    path      : ").append(d.getDeviceName()).append('\n');
            sb.append("    class     : ").append(d.getDeviceClass())
              .append('/').append(d.getDeviceSubclass()).append('\n');
            sb.append("    permission: ")
              .append(manager.hasPermission(d) ? "granted" : "NOT granted").append('\n');
            sb.append("    driver    : ");
            if (driver == null) {
                sb.append("none — not a recognised serial chip");
            } else {
                sb.append(driver.getClass().getSimpleName())
                  .append(" (").append(driver.getPorts().size()).append(" port(s))");
                if (guessed) sb.append("  [guess]");
            }
            sb.append('\n');

            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface itf = d.getInterface(i);
                sb.append(String.format(Locale.US,
                        "    itf %d     : class=%d subclass=%d proto=%d endpoints=%d\n",
                        i, itf.getInterfaceClass(), itf.getInterfaceSubclass(),
                        itf.getInterfaceProtocol(), itf.getEndpointCount()));
            }

            if (driver == null) {
                sb.append("    → Set Setup ▸ Force driver, then reconnect.\n");
            } else if (guessed) {
                sb.append("    → Matched by fallback, not by a known id. If it misbehaves,\n")
                  .append("      try Force driver ▸ CDC-ACM instead.\n");
            }
        }
        return sb.toString();
    }
}
