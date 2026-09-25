import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

class MysqlExec {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("SQL statement required");
        String user = System.getenv("E2E_MYSQL_USER");
        String password = System.getenv("E2E_MYSQL_PASSWORD");
        if (user == null || password == null) throw new IllegalStateException("MySQL credentials required");
        String url = "jdbc:mysql://127.0.0.1:3306/mannschaft?serverTimezone=Asia/Tokyo&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            if (statement.execute(args[0])) {
                try (ResultSet result = statement.getResultSet()) {
                    while (result.next()) {
                        System.out.println(result.getString(1));
                    }
                }
            }
        }
    }
}
