package com.finsight.synthetic;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.finsight.financial.*;

/** Separate manual main class; never called by FinSightApplication or Spring startup. */
public final class SyntheticDataCli {
    private SyntheticDataCli() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            String message=(e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof java.io.IOException)?e.getMessage():"Check local database/configuration; connection details withheld";
            System.err.println("Synthetic command failed: "+message); System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        if (args.length<1 || !Set.of("generate","load","verify").contains(args[0])) throw new IllegalArgumentException("Use generate|load|verify [--seed N] [--output DIR for generate]");
        long seed=SyntheticDataset.DEFAULT_SEED; Path output=null;
        Set<String> seen=new HashSet<>();
        for (int i=1;i<args.length;i+=2) {
            if (i+1>=args.length || !seen.add(args[i])) throw new IllegalArgumentException("Missing/repeated option");
            switch (args[i]) {
                case "--seed" -> seed=Long.parseLong(args[i+1]);
                case "--output" -> output=Path.of(args[i+1]);
                default -> throw new IllegalArgumentException("Unknown option");
            }
        }
        if (output!=null && !args[0].equals("generate")) throw new IllegalArgumentException("--output applies only to generate");
        var data=SyntheticGenerator.generate(seed);
        System.out.println("SYNTHETIC ONLY | "+SyntheticDataset.VERSION+" | seed="+seed+" | "+data.start()+" through "+data.end());
        System.out.println("SMEs="+data.companies().size()+", invoices="+data.invoices().size()+", transactions="+data.transactions().size()+", fingerprint="+SyntheticExporter.fingerprint(data));
        if (args[0].equals("generate")) {
            SyntheticExporter.export(data,output==null?Path.of("data/synthetic/FS1-"+seed):output);
            System.out.println("Exported/verified matching CSV artifacts and manifest. No database connection used."); return;
        }
        String url=required("DB_URL"),user=required("DB_USER"),password=required("DB_PASSWORD");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/finsight") || !user.equals("finsight_app")) throw new IllegalArgumentException("CLI accepts only loopback finsight / finsight_app; tests use their guarded datasource directly");
        var source=new DriverManagerDataSource(url,user,password);
        var jdbc=new JdbcTemplate(source);
        if (!Boolean.FALSE.equals(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user",Boolean.class))) throw new IllegalArgumentException("Synthetic CLI must not use a superuser");
        var reports=new FinancialReportService(new FinancialReadRepository(sourceClient(source)),new FinancialCalculator(),new BigDecimal("0.00"),3660);
        var loader=new SyntheticDataLoader(source,reports);
        var result=args[0].equals("load")?loader.load(data):loader.verify(data);
        System.out.println(result.inserted()?"Inserted and verified atomically.":"Verified existing dataset; no rows inserted or overwritten.");
        result.companyIds().forEach((key,id)->System.out.println("SYNTHETIC "+key+" -> SME id="+id+", opening="+data.companies().stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().opening()+", closing="+result.closingBalances().get(key)+" USD"));
    }
    private static org.springframework.jdbc.core.simple.JdbcClient sourceClient(javax.sql.DataSource source) {
        return org.springframework.jdbc.core.simple.JdbcClient.create(source);
    }
    private static String required(String name) {
        String value=System.getenv(name); if (value==null || value.isBlank()) throw new IllegalArgumentException("Export "+name+" from ignored .env"); return value;
    }
}
