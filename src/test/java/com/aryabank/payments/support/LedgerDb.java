package com.aryabank.payments.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only JDBC access to the API's SQLite database.
 *
 * The API keeps a double-entry ledger, so the database itself can prove
 * whether money was created or lost — independent of what the API reports.
 */
public final class LedgerDb {

    private LedgerDb() {
    }

    public record Entry(String txnId, String accountId, String direction, BigDecimal amount) {
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + Config.DB_PATH);
    }

    private static BigDecimal rupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    /** All ledger entries written for one transaction id (transfer, refund or account opening). */
    public static List<Entry> entriesFor(String txnId) {
        String sql = "SELECT txn_id, account_id, direction, amount_paise FROM ledger_entries WHERE txn_id = ? ORDER BY entry_id";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, txnId);
            List<Entry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rupees(rs.getLong(4))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Balance stored on the account row. */
    public static BigDecimal storedBalance(String accountId) {
        return rupees(queryLong("SELECT balance_paise FROM accounts WHERE account_id = ?", accountId));
    }

    /** Balance rebuilt from the ledger: credits minus debits. */
    public static BigDecimal ledgerBalance(String accountId) {
        return rupees(queryLong(
                "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END), 0) "
                        + "FROM ledger_entries WHERE account_id = ?", accountId));
    }

    /** Number of completed transfers (not refunds) between two accounts. */
    public static long transferCount(String from, String to) {
        return queryLong("SELECT COUNT(*) FROM transfers WHERE kind = 'TRANSFER' AND from_account = ? AND to_account = ?",
                from, to);
    }

    /** Transaction ids whose debits and credits do not add up. Should always be empty. */
    public static List<String> unbalancedTransactions() {
        return queryStrings("SELECT txn_id FROM ledger_entries GROUP BY txn_id "
                + "HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount_paise ELSE -amount_paise END) <> 0");
    }

    /** Accounts whose stored balance disagrees with their ledger. Should always be empty. */
    public static List<String> accountsOutOfSync() {
        return queryStrings("SELECT a.account_id FROM accounts a WHERE a.balance_paise <> COALESCE(("
                + "SELECT SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount_paise ELSE -l.amount_paise END) "
                + "FROM ledger_entries l WHERE l.account_id = a.account_id), 0)");
    }

    /** Customer accounts below zero. Should always be empty (only the system funding account may go negative). */
    public static List<String> negativeCustomerBalances() {
        return queryStrings("SELECT account_id FROM accounts WHERE kind = 'CUSTOMER' AND balance_paise < 0");
    }

    /** Ledger rows with a zero or negative amount. Should always be empty. */
    public static List<String> nonPositiveEntries() {
        return queryStrings("SELECT txn_id FROM ledger_entries WHERE amount_paise <= 0");
    }

    /** Sum of every balance, system account included. Money is only moved, never created, so this is 0.00. */
    public static BigDecimal totalMoney() {
        return rupees(queryLong("SELECT COALESCE(SUM(balance_paise), 0) FROM accounts"));
    }

    private static long queryLong(String sql, Object... params) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No row for: " + sql);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> queryStrings(String sql) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
