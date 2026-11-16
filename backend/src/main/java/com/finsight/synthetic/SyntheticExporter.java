package com.finsight.synthetic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.finsight.financial.FinancialCalculator;
import static com.finsight.financial.FinancialModels.*;

/** Deterministic UTF-8/LF CSV + manifest; no live database IDs, credentials, or timestamps. */
public final class SyntheticExporter {
    private SyntheticExporter() { }
    private static void row(StringBuilder out,Object... values) {
        for (int i=0;i<values.length;i++) {
            if (i>0) out.append(',');
            String value=values[i]==null?"":values[i].toString();
            out.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        out.append('\n');
    }
    public static Map<String,String> files(SyntheticDataset data) {
        data.validate();
        Map<String,String> files=new TreeMap<>();
        StringBuilder companies=new StringBuilder(),openings=new StringBuilder(),invoices=new StringBuilder(),cash=new StringBuilder(),daily=new StringBuilder();
        row(companies,"is_synthetic","company_key","name","currency");
        row(openings,"is_synthetic","company_key","opening_date","currency","opening_balance");
        row(invoices,"is_synthetic","invoice_key","company_key","currency","direction","amount","issue_date","due_date","payment_status_at_cutoff");
        row(cash,"is_synthetic","transaction_key","company_key","currency","invoice_key","booked_date","direction","transaction_type","amount","description");
        row(daily,"is_synthetic","company_key","currency","date","opening_balance","inflow","outflow","net_movement","closing_balance");
        FinancialCalculator engine=new FinancialCalculator();
        for (int i=0;i<data.companies().size();i++) {
            var c=data.companies().get(i);
            row(companies,true,c.key(),c.name(),c.currency()); row(openings,true,c.key(),data.start(),c.currency(),c.opening());
            var report=engine.cashFlow(i+1L,new OpeningCash(data.start(),c.currency(),c.opening()),data.end(),data.movements(c.key()),new java.math.BigDecimal("0.00"),3660);
            for (var d:report.days()) row(daily,true,c.key(),c.currency(),d.date(),d.openingBalance(),d.inflow(),d.outflow(),d.netMovement(),d.closingBalance());
        }
        for (var b:data.invoices()) row(invoices,true,b.key(),b.companyKey(),data.currency(b.companyKey()),b.direction(),b.amount(),b.issued(),b.due(),b.status());
        for (var t:data.transactions()) row(cash,true,t.key(),t.companyKey(),data.currency(t.companyKey()),t.invoiceKey(),t.date(),t.direction(),t.type(),t.amount(),t.description());
        files.put("smes.csv",companies.toString()); files.put("opening_balances.csv",openings.toString());
        files.put("invoices.csv",invoices.toString()); files.put("transactions.csv",cash.toString()); files.put("daily_cash_flow.csv",daily.toString());
        StringBuilder manifest=new StringBuilder("{\n  \"is_synthetic\": true,\n  \"generator_version\": \"FS1\",\n  \"seed\": "+data.seed()+",\n  \"start_date\": \""+data.start()+"\",\n  \"end_date\": \""+data.end()+"\",\n");
        manifest.append("  \"currency\": \"USD\",\n  \"amount_scale\": 2,\n  \"sme_count\": ").append(data.companies().size()).append(",\n  \"opening_balance_count\": ").append(data.companies().size())
            .append(",\n  \"invoice_count\": ").append(data.invoices().size()).append(",\n  \"transaction_count\": ").append(data.transactions().size())
            .append(",\n  \"linked_payment_count\": ").append(data.transactions().stream().filter(t->t.invoiceKey()!=null).count())
            .append(",\n  \"daily_row_count\": ").append((java.time.temporal.ChronoUnit.DAYS.between(data.start(),data.end())+1)*data.companies().size()).append(",\n  \"sha256\": {\n");
        int index=0;
        for (var entry:files.entrySet()) {
            if (index++>0) manifest.append(",\n");
            manifest.append("    \"").append(entry.getKey()).append("\": \"").append(hash(entry.getValue())).append("\"");
        }
        manifest.append("\n  }\n}\n"); files.put("manifest.json",manifest.toString());
        return Collections.unmodifiableMap(files);
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String fingerprint(SyntheticDataset data) {
        StringBuilder text=new StringBuilder(); files(data).forEach((name,body)->text.append(name).append('\n').append(body)); return hash(text.toString());
    }
    public static void export(SyntheticDataset data,Path directory) throws IOException {
        Map<String,String> files=files(data);
        // Preflight every target before writing anything; never overwrite changed artifacts.
        for (var entry:files.entrySet()) {
            Path file=directory.resolve(entry.getKey());
            if (Files.exists(file) && !Files.readString(file,StandardCharsets.UTF_8).equals(entry.getValue())) throw new IOException("Existing artifact differs: "+file);
        }
        Files.createDirectories(directory);
        for (var entry:files.entrySet()) {
            Path file=directory.resolve(entry.getKey());
            if (!Files.exists(file)) Files.writeString(file,entry.getValue(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
    }
}
