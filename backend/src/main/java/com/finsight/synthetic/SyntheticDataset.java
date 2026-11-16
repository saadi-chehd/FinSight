package com.finsight.synthetic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import com.finsight.financial.FinancialCalculator;
import static com.finsight.financial.FinancialModels.*;

/** Versioned, fictional data only. Logical keys are independent of PostgreSQL identities. */
public record SyntheticDataset(long seed, LocalDate start, LocalDate end,
        List<Company> companies, List<Bill> invoices, List<Cash> transactions) {
    public static final String VERSION = "FS1";
    public static final long DEFAULT_SEED = 20261018L;
    public record Company(String key, String name, String currency, BigDecimal opening) { }
    public record Bill(String key, String companyKey, Direction direction, BigDecimal amount,
                       LocalDate issued, LocalDate due, PaymentStatus status) { }
    public record Cash(String key, String companyKey, String invoiceKey, LocalDate date,
                       Direction direction, String type, BigDecimal amount, String description) { }
    public SyntheticDataset {
        companies = List.copyOf(companies); invoices = List.copyOf(invoices); transactions = List.copyOf(transactions);
    }

    public List<Movement> movements(String companyKey) {
        Map<String,Long> invoiceIds = new HashMap<>();
        for (int i=0;i<invoices.size();i++) invoiceIds.put(invoices.get(i).key(),(long)i+1);
        List<Movement> result = new ArrayList<>();
        for (int i=0;i<transactions.size();i++) {
            Cash tx=transactions.get(i);
            if (tx.companyKey().equals(companyKey)) result.add(new Movement(i+1L,
                tx.invoiceKey()==null?null:invoiceIds.get(tx.invoiceKey()),tx.date(),currency(companyKey),tx.direction(),tx.amount()));
        }
        return result;
    }
    public String currency(String key) {
        return companies.stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().currency();
    }
    public List<Invoice> invoiceModels(String key) {
        List<Invoice> result=new ArrayList<>();
        for (int i=0;i<invoices.size();i++) {
            Bill b=invoices.get(i);
            if (b.companyKey().equals(key)) result.add(new Invoice(i+1L,b.key(),currency(key),b.direction(),b.amount(),b.issued(),b.due(),b.status()));
        }
        return result;
    }

    /** Fail before export/load if generated data violate key, payment, money, or engine rules. */
    public void validate() {
        if (seed<0 || start==null || end==null || end.isBefore(start)) throw new IllegalArgumentException("Invalid synthetic range/seed");
        Set<String> companyKeys=new HashSet<>(), names=new HashSet<>(), txKeys=new HashSet<>();
        Map<String,Bill> bills=new HashMap<>();
        for (Company c:companies) {
            if (!companyKeys.add(c.key()) || !names.add(c.name()) || !c.name().startsWith("SYNTHETIC " + c.key()) || !c.key().startsWith(VERSION+"-"+seed+"-")) throw new IllegalArgumentException("Invalid synthetic company namespace");
        }
        for (Bill b:invoices) {
            if (!companyKeys.contains(b.companyKey()) || bills.putIfAbsent(b.key(),b)!=null || b.key().length()>100
                    || !b.key().startsWith(b.companyKey()+"-I") || b.issued().isBefore(start) || b.issued().isAfter(end)
                    || b.due().isBefore(b.issued()) || b.status()==PaymentStatus.CANCELLED) throw new IllegalArgumentException("Invalid synthetic invoice");
        }
        Map<String,BigDecimal> paid=new HashMap<>();
        for (Cash tx:transactions) {
            if (!companyKeys.contains(tx.companyKey()) || !txKeys.add(tx.key()) || !tx.key().startsWith(tx.companyKey()+"-T")
                    || !tx.description().startsWith("SYNTHETIC " + tx.key()+" ") || tx.description().length()>500
                    || tx.date().isBefore(start) || tx.date().isAfter(end)) throw new IllegalArgumentException("Invalid synthetic movement");
            Set<String> allowed=tx.direction()==Direction.INFLOW?Set.of("CUSTOMER_RECEIPT","FINANCING_RECEIPT","OTHER"):
                Set.of("SUPPLIER_PAYMENT","PAYROLL","TAX","FINANCING_REPAYMENT","OTHER");
            if (!allowed.contains(tx.type())) throw new IllegalArgumentException("Invalid movement type/direction");
            if (tx.invoiceKey()!=null) {
                Bill b=bills.get(tx.invoiceKey());
                if (b==null || !b.companyKey().equals(tx.companyKey()) || b.direction()!=tx.direction()
                        || tx.date().isBefore(b.issued())) throw new IllegalArgumentException("Invalid synthetic payment link");
                paid.merge(b.key(),tx.amount(),BigDecimal::add);
            }
        }
        for (Bill b:invoices) {
            BigDecimal amount=paid.getOrDefault(b.key(),new BigDecimal("0.00"));
            PaymentStatus status=amount.signum()==0?PaymentStatus.UNPAID:amount.compareTo(b.amount())<0?PaymentStatus.PARTIALLY_PAID:PaymentStatus.PAID;
            if (amount.compareTo(b.amount())>0 || b.status()!=status) throw new IllegalArgumentException("Invoice/payment status mismatch or overpayment");
        }
        FinancialCalculator calculator=new FinancialCalculator();
        for (int i=0;i<companies.size();i++) {
            Company c=companies.get(i);
            var movements=movements(c.key());
            calculator.cashFlow(i+1L,new OpeningCash(start,c.currency(),c.opening()),end,movements,new BigDecimal("0.00"),3660);
            var summary=calculator.invoiceSummary(i+1L,c.currency(),end,invoiceModels(c.key()),movements);
            if (summary.invoices().stream().anyMatch(InvoicePosition::statusMismatch)) throw new IllegalArgumentException("Synthetic status mismatch");
        }
    }
}
