package com.finsight.synthetic;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.finsight.synthetic.SyntheticDataset.*;
import static com.finsight.financial.FinancialModels.*;

/** Stylized assumptions, not estimated real SME behavior. Java Random + integer minor units. */
public final class SyntheticGenerator {
    public static final LocalDate START=LocalDate.of(2024,1,1);
    public static final LocalDate END=LocalDate.of(2025,12,31);
    private static final int[] SEASON={85,90,100,105,100,110,95,90,105,110,120,130};
    private SyntheticGenerator() { }
    private static BigDecimal money(long cents) { return BigDecimal.valueOf(cents,2); }

    public static SyntheticDataset generate(long seed) {
        if (seed<0) throw new IllegalArgumentException("Seed must be nonnegative");
        List<Company> companies=new ArrayList<>(); List<Bill> invoices=new ArrayList<>(); List<Cash> cash=new ArrayList<>();
        String[] profiles={"Fictional Retail","Fictional Agency","Fictional Workshop"};
        long[] openings={500000,1200000,800000};
        int[] invoiceBases={40000,100000,80000}, payroll={230000,430000,320000}, rent={80000,120000,100000};
        for (int profile=0;profile<3;profile++) {
            String key=VERSION+"-"+seed+"-"+(char)('A'+profile);
            companies.add(new Company(key,"SYNTHETIC "+key+" "+profiles[profile],"USD",money(openings[profile])));
            Random random=new Random(seed ^ (0x5DEECE66DL*(profile+1)));
            int invoiceNumber=0;
            List<Cash> companyCash=new ArrayList<>();
            for (LocalDate date=START;!date.isAfter(END);date=date.plusDays(1)) {
                int factor=SEASON[date.getMonthValue()-1]+5*(date.getYear()-2024);
                DayOfWeek day=date.getDayOfWeek();
                boolean receivable=day==DayOfWeek.TUESDAY || day==DayOfWeek.FRIDAY;
                if (receivable || day==DayOfWeek.WEDNESDAY) {
                    int number=++invoiceNumber;
                    String invoiceKey=key+"-I"+String.format(Locale.ROOT,"%04d",number);
                    Direction direction=receivable?Direction.INFLOW:Direction.OUTFLOW;
                    int base=receivable?invoiceBases[profile]:15000;
                    long cents=(base+random.nextInt(receivable?base*2:45000))*factor/100;
                    LocalDate due=date.plusDays(new int[]{14,30,45}[random.nextInt(3)]);
                    List<Cash> payments=new ArrayList<>();
                    int mode=random.nextInt(6);
                    switch (mode) {
                        case 0 -> payment(payments,key,invoiceKey,date,due.minusDays(random.nextInt(4)),direction,cents);
                        case 1 -> payment(payments,key,invoiceKey,date,due.plusDays(3+random.nextInt(19)),direction,cents);
                        case 2 -> {
                            long first=cents*40/100;
                            payment(payments,key,invoiceKey,date,date.plusDays(7),direction,first);
                            payment(payments,key,invoiceKey,date,due.plusDays(3+random.nextInt(12)),direction,cents-first);
                        }
                        case 3 -> payment(payments,key,invoiceKey,date,date.plusDays(7),direction,cents*35/100);
                        case 4 -> { /* Intentionally unpaid, including old overdue invoices. */ }
                        case 5 -> payment(payments,key,invoiceKey,date,date.plusDays(2+random.nextInt(6)),direction,cents);
                        default -> throw new IllegalStateException();
                    }
                    BigDecimal observed=payments.stream().map(Cash::amount).reduce(money(0),BigDecimal::add);
                    PaymentStatus status=observed.signum()==0?PaymentStatus.UNPAID:observed.compareTo(money(cents))<0?PaymentStatus.PARTIALLY_PAID:PaymentStatus.PAID;
                    invoices.add(new Bill(invoiceKey,key,direction,money(cents),date,due,status));
                    companyCash.addAll(payments);
                }
                boolean weekday=day.getValue()<=5;
                int sales=profile==1?0:random.nextInt(profile==0?3:2);
                if (!weekday && random.nextInt(100)>=35) sales=0;
                for (int sale=0;sale<sales;sale++) {
                    long cents=(5000+random.nextInt(25000))*factor/100;
                    companyCash.add(raw(key,date,Direction.INFLOW,"CUSTOMER_RECEIPT",cents,"direct sale"));
                }
                if (weekday && random.nextInt(100)<35) companyCash.add(raw(key,date,Direction.OUTFLOW,"OTHER",2000+random.nextInt(16000),"operating expense"));
                if (date.getDayOfMonth()==1) companyCash.add(raw(key,date,Direction.OUTFLOW,"OTHER",rent[profile],"rent"));
                if (date.getDayOfMonth()==25) companyCash.add(raw(key,date,Direction.OUTFLOW,"PAYROLL",payroll[profile]+random.nextInt(20000),"payroll"));
                if (date.getDayOfMonth()==20 && date.getMonthValue()%3==0) companyCash.add(raw(key,date,Direction.OUTFLOW,"TAX",35000+random.nextInt(25000),"assumed tax payment"));
            }
            // Stable sort preserves the order of same-day generation events.
            companyCash.sort(Comparator.comparing(Cash::date));
            for (int i=0;i<companyCash.size();i++) {
                Cash tx=companyCash.get(i);
                String txKey=key+"-T"+String.format(Locale.ROOT,"%05d",i+1);
                cash.add(new Cash(txKey,key,tx.invoiceKey(),tx.date(),tx.direction(),tx.type(),tx.amount(),"SYNTHETIC "+txKey+" "+tx.description()));
            }
        }
        SyntheticDataset dataset=new SyntheticDataset(seed,START,END,companies,invoices,cash);
        dataset.validate();
        return dataset;
    }
    private static Cash raw(String company,LocalDate date,Direction direction,String type,long cents,String purpose) {
        return new Cash("pending",company,null,date,direction,type,money(cents),purpose);
    }
    private static void payment(List<Cash> payments,String company,String invoice,LocalDate issued,LocalDate date,Direction direction,long cents) {
        if (date.isBefore(issued) || date.isAfter(END)) return;
        payments.add(new Cash("pending",company,invoice,date,direction,
            direction==Direction.INFLOW?"CUSTOMER_RECEIPT":"SUPPLIER_PAYMENT",money(cents),"invoice settlement"));
    }
}
