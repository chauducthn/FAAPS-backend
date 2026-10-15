import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class BuildRdsTruststore {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected certificate bundle and truststore paths");
        }

        KeyStore truststore = KeyStore.getInstance("PKCS12");
        truststore.load(null, null);
        int rootCount = 0;
        try (FileInputStream input = new FileInputStream(args[0])) {
            for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                X509Certificate x509 = (X509Certificate) certificate;
                if (x509.getBasicConstraints() >= 0
                        && x509.getSubjectX500Principal().equals(x509.getIssuerX500Principal())) {
                    truststore.setCertificateEntry("rds-root-" + rootCount++, x509);
                }
            }
        }
        if (rootCount == 0) {
            throw new IllegalStateException("No root CA certificates found in the RDS bundle");
        }
        try (FileOutputStream output = new FileOutputStream(args[1])) {
            truststore.store(output, "changeit".toCharArray());
        }
        System.out.println("Imported " + rootCount + " public RDS root CA certificates");
    }
}
