package com.pasadita.pos;

import com.pasadita.pos.dto.SaleDetailDTO;
import com.pasadita.pos.dto.TicketDTO;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class ESCPOSPrinter {
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    private static final byte[] INIT = {0x1B, 0x40};
    private static final byte[] CUT_PAPER = {0x1D, 0x56, 0x00};
    private static final byte[] CUT_PAPER_PARTIAL = {0x1D, 0x56, 0x01};
    private static final byte[] CUT_PAPER_FEED = {0x1D, 0x56, 0x42, 0x03};
    private static final byte[] LINE_FEED = {0x0A};
    private static final byte[] ALIGN_LEFT = {0x1B, 0x61, 0x00};
    private static final byte[] ALIGN_CENTER = {0x1B, 0x61, 0x01};
    private static final byte[] ALIGN_RIGHT = {0x1B, 0x61, 0x02};
    private static final byte[] BOLD_ON = {0x1B, 0x45, 0x01};
    private static final byte[] BOLD_OFF = {0x1B, 0x45, 0x00};
    private static final byte[] DOUBLE_HEIGHT_ON = {0x1B, 0x21, 0x10};
    private static final byte[] DOUBLE_WIDTH_ON = {0x1B, 0x21, 0x20};
    private static final byte[] DOUBLE_SIZE_ON = {0x1B, 0x21, 0x30};
    private static final byte[] NORMAL_SIZE = {0x1B, 0x21, 0x00};
    private static final byte[] UNDERLINE_ON = {0x1B, 0x2D, 0x01};
    private static final byte[] UNDERLINE_OFF = {0x1B, 0x2D, 0x00};
    private static final byte[] CHARSET_PC850 = {0x1B, 0x74, 0x02};
    private static final byte[] OPEN_DRAWER = {0x1B, 0x70, 0x00, 0x19, (byte) 0xFA};

    private static final int TICKET_WIDTH = 42;
    private static final String SEPARATOR = "------------------------------------------";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final String businessName;
    private final String businessAddress;
    private final String businessPhone;
    private final String printerPath;
    private final String printerName;

    private final ReentrantLock deviceLock = new ReentrantLock(true);

    public ESCPOSPrinter(String businessName, String businessAddress, String businessPhone,
                         String printerPath, String printerName) {
        this.businessName = businessName;
        this.businessAddress = businessAddress;
        this.businessPhone = businessPhone;
        this.printerPath = printerPath != null ? printerPath : DEFAULT_PRINTER_PATH;
        this.printerName = printerName;
    }

    public ESCPOSPrinter(String businessName, String businessAddress, String businessPhone, String printerPath) {
        this(businessName, businessAddress, businessPhone, printerPath, null);
    }

    public ESCPOSPrinter(String businessName, String businessAddress, String businessPhone) {
        this(businessName, businessAddress, businessPhone, DEFAULT_PRINTER_PATH, null);
    }

    public ESCPOSPrinter() {
        this("LA PASADITA", "", "", DEFAULT_PRINTER_PATH, null);
    }

    public void print(TicketDTO ticket) throws PrinterException, IOException {

        byte[] commands = generateESCPOS(ticket);

        if (IS_WINDOWS) {
            printToWindowsPrinter(commands, "Ticket #" + ticket.getId());
        } else {
            printToLinuxDevice(commands, "Ticket #" + ticket.getId());
        }
    }

    public void openCashDrawer() {
        try {
            if (IS_WINDOWS) {
                printToWindowsPrinter(OPEN_DRAWER, "Apertura de cajon");
            } else {
                printToLinuxDevice(OPEN_DRAWER, "Apertura de cajon");
            }
            System.out.println("[OK] Cajon abierto correctamente");
        } catch (PrinterException e) {
            System.err.println("[ERROR] No se pudo abrir el cajon: " + e.getMessage());
        }
    }

    private void printToLinuxDevice(byte[] data, String description) throws PrinterException {
        deviceLock.lock();
        try {

            try (FileOutputStream fos = new FileOutputStream(printerPath)) {
                fos.write(data);
                fos.flush();
                System.out.println("[OK] " + description + " enviado a impresora correctamente");
            } catch (IOException e) {
                System.err.println("[ERROR] Error escribiendo a impresora: " + e.getMessage());
                throw new PrinterException("No se pudo escribir en " + printerPath + ": " + e.getMessage(), e);
            }
        } finally {
            deviceLock.unlock();
        }
    }

    private void printToWindowsPrinter(byte[] data, String description) throws PrinterException {
        deviceLock.lock();
        try {
            String effectivePrinterName = getEffectivePrinterName();

            PrintService printService = findPrintService(effectivePrinterName);
            if (printService == null) {
                throw new PrinterException("Impresora no encontrada: " + effectivePrinterName +
                        ". Impresoras disponibles: " + getAvailablePrinters());
            }

            try {
                DocPrintJob printJob = printService.createPrintJob();
                DocFlavor flavor = DocFlavor.BYTE_ARRAY.AUTOSENSE;
                Doc doc = new SimpleDoc(data, flavor, null);
                PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();

                printJob.print(doc, attributes);
                System.out.println("[OK] " + description + " enviado a impresora correctamente");
            } catch (javax.print.PrintException e) {
                System.err.println("[ERROR] Error de impresión: " + e.getMessage());
                throw new PrinterException("Error al imprimir en " + effectivePrinterName + ": " + e.getMessage(), e);
            }
        } finally {
            deviceLock.unlock();
        }
    }

    private String getEffectivePrinterName() {
        if (printerName != null && !printerName.isEmpty()) {
            return printerName;
        }

        if (printerPath != null && !printerPath.startsWith("/dev/")) {
            return printerPath;
        }
        return null;
    }

    private PrintService findPrintService(String printerName) {
        if (printerName == null) {
            return null;
        }
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService service : services) {
            if (service.getName().equalsIgnoreCase(printerName) ||
                    service.getName().toLowerCase().contains(printerName.toLowerCase())) {
                return service;
            }
        }
        return null;
    }

    public String getAvailablePrinters() {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < services.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(services[i].getName());
        }
        return sb.toString();
    }

    private boolean isDeliveryOrder(TicketDTO ticket) {
        return ticket.getDeliveryOrderId() != null && ticket.getDeliveryAddress() != null
                && !ticket.getDeliveryAddress().isEmpty();
    }

    public byte[] generateESCPOS(TicketDTO ticket) throws IOException {
        boolean isDelivery = isDeliveryOrder(ticket);

        if (isDelivery) {
            return generateDeliveryTicket(ticket);
        } else {
            return generateCashRegisterTicket(ticket);
        }
    }

    private byte[] generateCashRegisterTicket(TicketDTO ticket) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        buffer.write(INIT);
        buffer.write(OPEN_DRAWER);

        buffer.write(CHARSET_PC850);

        writeHeader(buffer);

        writeTicketInfoCashRegister(buffer, ticket);

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        writeDetails(buffer, ticket.getSaleDetails());

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        writeTotals(buffer, ticket);

        writeFooterCashRegister(buffer, ticket);

        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(CUT_PAPER_FEED);

        return buffer.toByteArray();
    }

    private byte[] generateDeliveryTicket(TicketDTO ticket) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        buffer.write(INIT);

        buffer.write(CHARSET_PC850);

        writeHeader(buffer);

        buffer.write(ALIGN_CENTER);
        buffer.write(BOLD_ON);
        buffer.write(DOUBLE_SIZE_ON);
        buffer.write("PEDIDO A DOMICILIO".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        writeTicketInfoDelivery(buffer, ticket);

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        writeDetails(buffer, ticket.getSaleDetails());

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        writeTotals(buffer, ticket);

        writeFooterDelivery(buffer, ticket);

        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(CUT_PAPER_FEED);

        return buffer.toByteArray();
    }

    private void writeTicketInfoCashRegister(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(ALIGN_LEFT);

        buffer.write(BOLD_ON);
        buffer.write(("TICKET #" + ticket.getId()).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        if (ticket.getDatetime() != null) {
            buffer.write(("Fecha: " + ticket.getDatetime().format(DATE_FORMAT)).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getEmployeeName() != null) {
            buffer.write(("Cajero: " + ticket.getEmployeeName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getCustomerName() != null && !ticket.getCustomerName().isEmpty()) {
            buffer.write(("Cliente: " + ticket.getCustomerName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getPaymentMethodName() != null) {
            buffer.write(("Pago: " + ticket.getPaymentMethodName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);
    }

    private void writeTicketInfoDelivery(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(ALIGN_LEFT);

        buffer.write(BOLD_ON);
        buffer.write(DOUBLE_HEIGHT_ON);
        buffer.write(("PEDIDO #" + ticket.getDeliveryOrderId()).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        buffer.write(("Ticket Venta: #" + ticket.getId()).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        if (ticket.getDatetime() != null) {
            buffer.write(("Fecha: " + ticket.getDatetime().format(DATE_FORMAT)).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);

        buffer.write(BOLD_ON);
        buffer.write("-------- DATOS DEL CLIENTE ---------------".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        if (ticket.getCustomerName() != null && !ticket.getCustomerName().isEmpty()) {
            buffer.write(("Nombre: " + ticket.getCustomerName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getCustomerDiscount() != null && ticket.getCustomerDiscount().compareTo(BigDecimal.ZERO) > 0) {
            buffer.write(("Descuento Cliente: " + formatPrice(ticket.getCustomerDiscount())).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getCustomerPhone() != null && !ticket.getCustomerPhone().isEmpty()) {
            buffer.write(("Telefono: " + ticket.getCustomerPhone()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getDeliveryAddress() != null && !ticket.getDeliveryAddress().isEmpty()) {
            buffer.write(BOLD_ON);
            buffer.write("Direccion:".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(BOLD_OFF);
            buffer.write(LINE_FEED);
            writeWrappedText(buffer, ticket.getDeliveryAddress());
        }

        buffer.write(LINE_FEED);

        if (ticket.getEmployeeName() != null) {
            buffer.write(("Atendio: " + ticket.getEmployeeName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        if (ticket.getPaymentMethodName() != null) {
            buffer.write(("Forma de Pago: " + ticket.getPaymentMethodName()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);
    }

    private void writeWrappedText(ByteArrayOutputStream buffer, String text) throws IOException {
        if (text == null || text.isEmpty()) return;

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + ESCPOSPrinter.TICKET_WIDTH, text.length());
            buffer.write(text.substring(start, end).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
            start = end;
        }
    }

    private void writeFooterCashRegister(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_CENTER);
        buffer.write(LINE_FEED);

        if (ticket.getNotes() != null && !ticket.getNotes().isEmpty()) {
            buffer.write(LINE_FEED);
            buffer.write(("Nota: " + ticket.getNotes()).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(LINE_FEED);
        buffer.write("Gracias por su compra!".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_LEFT);
    }

    private void writeFooterDelivery(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_CENTER);

        if (ticket.getNotes() != null && !ticket.getNotes().isEmpty()) {
            buffer.write(LINE_FEED);
            buffer.write(BOLD_ON);
            buffer.write("NOTAS:".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(BOLD_OFF);
            buffer.write(LINE_FEED);
            writeWrappedText(buffer, ticket.getNotes());
        }

        buffer.write(LINE_FEED);
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write("Gracias por su preferencia!".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(ALIGN_LEFT);
    }

    private void writeHeader(ByteArrayOutputStream buffer) throws IOException {
        buffer.write(ALIGN_CENTER);
        buffer.write(DOUBLE_SIZE_ON);
        buffer.write(businessName.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(NORMAL_SIZE);

        if (businessAddress != null && !businessAddress.isEmpty()) {
            buffer.write(businessAddress.getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }
        if (businessPhone != null && !businessPhone.isEmpty()) {
            buffer.write(("WhatsApp: " + businessPhone).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }
        buffer.write(LINE_FEED);
    }

    private void writeDetails(ByteArrayOutputStream buffer, List<SaleDetailDTO> details) throws IOException {
        if (details == null || details.isEmpty()) {
            buffer.write("(Sin productos)".getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
            return;
        }

        buffer.write(BOLD_ON);
        buffer.write(formatDetailLine("CANT", "PRODUCTO", "PRECIO", "TOTAL").getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);
        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        for (SaleDetailDTO detail : details) {
            String productName = detail.getProductName() != null ? detail.getProductName() : "";
            String qty = formatQuantity(detail.getQuantity());
            String unitPrice = formatPrice(detail.getUnitPrice());
            String total = formatPrice(detail.getSubtotal());

            if (productName.length() <= 18) {
                buffer.write(formatDetailLine(qty, productName, unitPrice, total).getBytes(StandardCharsets.ISO_8859_1));
                buffer.write(LINE_FEED);
            } else {
                List<String> lines = splitProductName(productName);

                buffer.write(formatDetailLine(qty, lines.get(0), unitPrice, total).getBytes(StandardCharsets.ISO_8859_1));
                buffer.write(LINE_FEED);

                for (int i = 1; i < lines.size(); i++) {
                    buffer.write(formatDetailLine("", lines.get(i), "", "").getBytes(StandardCharsets.ISO_8859_1));
                    buffer.write(LINE_FEED);
                }
            }
        }
    }

    private List<String> splitProductName(String name) {
        List<String> lines = new java.util.ArrayList<>();

        if (name == null || name.isEmpty()) {
            lines.add("");
            return lines;
        }

        String remaining = name.trim();

        while (remaining.length() > 18) {
            int splitPos = remaining.lastIndexOf(' ', 18);

            if (splitPos <= 0) {
                splitPos = 18;
            }

            lines.add(remaining.substring(0, splitPos).trim());
            remaining = remaining.substring(splitPos).trim();
        }

        if (!remaining.isEmpty()) {
            lines.add(remaining);
        }

        return lines;
    }

    private void writeTotals(ByteArrayOutputStream buffer, TicketDTO ticket) throws IOException {
        buffer.write(ALIGN_RIGHT);

        buffer.write(("Subtotal: $" + formatPrice(ticket.getSubtotal())).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        if (ticket.getDiscountAmount() != null && ticket.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            buffer.write(("Descuento: -$" + formatPrice(ticket.getDiscountAmount())).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(BOLD_ON);
        buffer.write(DOUBLE_HEIGHT_ON);
        buffer.write(("TOTAL: $" + formatPrice(ticket.getTotal())).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(BOLD_OFF);
        buffer.write(LINE_FEED);

        Long paymentMethodId = ticket.getPaymentMethodId();
        boolean isCash = paymentMethodId != null && paymentMethodId == 1L;
        if (isCash) {
            buffer.write(("Recibido: $" + formatPrice(ticket.getAmountTendered())).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
            buffer.write(("Cambio: $" + formatPrice(ticket.getChangeDue())).getBytes(StandardCharsets.ISO_8859_1));
            buffer.write(LINE_FEED);
        }

        buffer.write(ALIGN_LEFT);
    }

    private String formatDetailLine(String cant, String producto, String precio, String total) {
        int cantWidth = 5;
        int productoWidth = 18;
        int precioWidth = 8;
        int totalWidth = 8;

        cant = cant != null ? cant : "";
        producto = producto != null ? producto : "";
        precio = precio != null ? precio : "";
        total = total != null ? total : "";

        if (cant.length() > cantWidth) cant = cant.substring(0, cantWidth);
        if (producto.length() > productoWidth) producto = producto.substring(0, productoWidth);
        if (precio.length() > precioWidth) precio = precio.substring(0, precioWidth);
        if (total.length() > totalWidth) total = total.substring(0, totalWidth);

        return String.format("%" + cantWidth + "s %-" + productoWidth + "s %" + precioWidth + "s %" + totalWidth + "s",
                cant, producto, precio, total);
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "0.00";
        return String.format("%.2f", price);
    }

    private String formatQuantity(BigDecimal qty) {
        if (qty == null) return "0";
        if (qty.stripTrailingZeros().scale() <= 0) {
            return qty.toBigInteger().toString();
        }
        return qty.toString();
    }

    public void printTestPage() throws PrinterException, IOException {

        String printerInfo = IS_WINDOWS ? getEffectivePrinterName() : printerPath;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(INIT);
        buffer.write(OPEN_DRAWER);
        buffer.write(CHARSET_PC850);
        buffer.write(ALIGN_CENTER);

        buffer.write(DOUBLE_SIZE_ON);
        buffer.write("PRINT TEST".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(NORMAL_SIZE);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);

        buffer.write("POS Printer Agent".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write("Connection successful!".getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(("Sistema: " + (IS_WINDOWS ? "Windows" : "Linux")).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(("Impresora: " + printerInfo).getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(SEPARATOR.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);
        buffer.write(LINE_FEED);

        buffer.write(CUT_PAPER_FEED);

        if (IS_WINDOWS) {
            printToWindowsPrinter(buffer.toByteArray(), "Pagina de prueba");
        } else {
            printToLinuxDevice(buffer.toByteArray(), "Pagina de prueba");
        }
    }

    public boolean isAvailable() {
        if (IS_WINDOWS) {
            String effectiveName = getEffectivePrinterName();
            return effectiveName != null && findPrintService(effectiveName) != null;
        } else {
            File printerDevice = new File(printerPath);
            return printerDevice.exists() && printerDevice.canWrite();
        }
    }

    public static boolean isWindows() {
        return IS_WINDOWS;
    }

    public static class PrinterException extends Exception {
        public PrinterException(String message) {
            super(message);
        }

        public PrinterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Deprecated
    public static class USBException extends PrinterException {
        public USBException(String message) {
            super(message);
        }

        public USBException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
