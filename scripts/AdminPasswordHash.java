import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminPasswordHash {
    public static void main(String[] args) throws Exception {
        String password = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Admin password is required");
        }
        System.out.println(new BCryptPasswordEncoder().encode(password));
    }
}
