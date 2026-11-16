package com.finsight.synthetic;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.finsight.synthetic.SyntheticDataset.*;
import static com.finsight.financial.FinancialModels.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticGeneratorTests {
    final SyntheticDataset data=SyntheticGenerator.generate(DEFAULT_SEED);

    @Test
    void seedAndVersionReproduceTheSameRecordsAndCanonicalFingerprint() {
        var again=SyntheticGenerator.generate(DEFAULT_SEED);
        assertEquals(data,again);
        assertEquals("d6751da8f13f5af6352ef126c637e14a6f67116a65ad665b8a628cd2cfa88e44",SyntheticExporter.fingerprint(data));
        assertEquals(SyntheticExporter.files(data),SyntheticExporter.files(again));
    }
    @Test
    void differentSeedChangesAmountsAndArtifacts() {
        var other=SyntheticGenerator.generate(DEFAULT_SEED+1);
        assertNotEquals(data.transactions(),other.transactions());
        assertNotEquals(SyntheticExporter.fingerprint(data),SyntheticExporter.fingerprint(other));
        assertThrows(IllegalArgumentException.class,()->SyntheticGenerator.generate(-1));
    }
    @Test
    void independentPaymentTotalsMatchEveryStatusWithoutOverpaymentOrDoubleCounting() {
        Set<PaymentStatus> statuses=new HashSet<>();
        boolean onTime=false,late=false,split=false;
        for (var bill:data.invoices()) {
            var payments=data.transactions().stream().filter(t->bill.key().equals(t.invoiceKey())).toList();
            BigDecimal paid=payments.stream().map(Cash::amount).reduce(new BigDecimal("0.00"),BigDecimal::add);
            assertTrue(paid.compareTo(bill.amount())<=0);
            var expected=paid.signum()==0?PaymentStatus.UNPAID:paid.compareTo(bill.amount())<0?PaymentStatus.PARTIALLY_PAID:PaymentStatus.PAID;
            assertEquals(expected,bill.status()); statuses.add(expected);
            for (var payment:payments) {
                assertEquals(bill.companyKey(),payment.companyKey()); assertEquals(bill.direction(),payment.direction());
                assertFalse(payment.date().isBefore(bill.issued())); assertFalse(payment.date().isAfter(data.end()));
                assertTrue(payment.description().endsWith("invoice settlement"));
            }
            if (expected==PaymentStatus.PAID) {
                var last=payments.stream().map(Cash::date).max(Comparator.naturalOrder()).orElseThrow();
                if (last.isAfter(bill.due())) late=true; else onTime=true;
            }
            if (payments.size()>1) split=true;
        }
        assertEquals(Set.of(PaymentStatus.UNPAID,PaymentStatus.PARTIALLY_PAID,PaymentStatus.PAID),statuses);
        assertTrue(onTime); assertTrue(late); assertTrue(split);
        assertTrue(data.transactions().stream().filter(t->t.invoiceKey()==null).noneMatch(t->t.description().endsWith("invoice settlement")));
    }
    @Test
    void calendarHistoryAndOpeningFilesHaveExactDocumentedCoverage() {
        var files=SyntheticExporter.files(data);
        assertEquals(3,data.companies().size()); assertEquals(942,data.invoices().size()); assertEquals(2514,data.transactions().size());
        assertEquals(2194,files.get("daily_cash_flow.csv").lines().count());
        assertEquals(4,files.get("opening_balances.csv").lines().count());
        assertTrue(files.get("daily_cash_flow.csv").contains("2024-02-29"));
        assertTrue(files.get("daily_cash_flow.csv").contains("2025-12-31"));
        assertTrue(files.get("manifest.json").contains("\"daily_row_count\": 2193"));
        for (var entry:files.entrySet()) if (!entry.getKey().equals("manifest.json")) {
            assertTrue(files.get("manifest.json").contains(SyntheticExporter.hash(entry.getValue())));
            assertTrue(entry.getValue().lines().skip(1).allMatch(line->line.startsWith("\"true\",")));
        }
    }
    @Test
    void exportsAreByteIdenticalAndRerunsDoNotOverwriteChanges(@TempDir Path root) throws Exception {
        Path first=root.resolve("first"),second=root.resolve("second");
        SyntheticExporter.export(data,first); SyntheticExporter.export(data,second); SyntheticExporter.export(data,first);
        for (var name:SyntheticExporter.files(data).keySet()) assertArrayEquals(Files.readAllBytes(first.resolve(name)),Files.readAllBytes(second.resolve(name)));
        Files.writeString(first.resolve("transactions.csv"),"user-edited artifact");
        assertThrows(java.io.IOException.class,()->SyntheticExporter.export(data,first));
        assertEquals("user-edited artifact",Files.readString(first.resolve("transactions.csv")));
    }
    @Test
    void provenanceAndAmountsAreExplicitAndNoOpeningIsAStoredMovement() {
        for (var company:data.companies()) {
            assertTrue(company.name().startsWith("SYNTHETIC FS1-")); assertEquals("USD",company.currency());
            assertEquals(2,company.opening().scale());
        }
        for (var tx:data.transactions()) {
            assertTrue(tx.description().startsWith("SYNTHETIC FS1-")); assertEquals(2,tx.amount().scale()); assertTrue(tx.amount().signum()>0);
            assertFalse(tx.description().toLowerCase(Locale.ROOT).contains("opening"));
        }
        assertTrue(data.invoices().stream().map(b->java.time.temporal.ChronoUnit.DAYS.between(b.issued(),b.due())).collect(java.util.stream.Collectors.toSet()).containsAll(Set.of(14L,30L,45L)));
    }
    @Test
    void inconsistentPaymentLinksAmountsAndStoredStatusAreRejected() {
        var tx=data.transactions().stream().filter(t->t.invoiceKey()!=null).findFirst().orElseThrow();
        var changed=new ArrayList<>(data.transactions());
        changed.set(changed.indexOf(tx),new Cash(tx.key(),tx.companyKey(),"missing-invoice",tx.date(),tx.direction(),tx.type(),tx.amount(),tx.description()));
        assertThrows(IllegalArgumentException.class,()->new SyntheticDataset(data.seed(),data.start(),data.end(),data.companies(),data.invoices(),changed).validate());
        var invoices=new ArrayList<>(data.invoices());
        var paid=invoices.stream().filter(b->b.status()==PaymentStatus.PAID).findFirst().orElseThrow();
        invoices.set(invoices.indexOf(paid),new Bill(paid.key(),paid.companyKey(),paid.direction(),paid.amount(),paid.issued(),paid.due(),PaymentStatus.UNPAID));
        assertThrows(IllegalArgumentException.class,()->new SyntheticDataset(data.seed(),data.start(),data.end(),data.companies(),invoices,data.transactions()).validate());
        var badMoney=new ArrayList<>(data.transactions());
        badMoney.set(badMoney.indexOf(tx),new Cash(tx.key(),tx.companyKey(),tx.invoiceKey(),tx.date(),tx.direction(),tx.type(),new BigDecimal("0.001"),tx.description()));
        assertThrows(IllegalArgumentException.class,()->new SyntheticDataset(data.seed(),data.start(),data.end(),data.companies(),data.invoices(),badMoney).validate());
    }
}
