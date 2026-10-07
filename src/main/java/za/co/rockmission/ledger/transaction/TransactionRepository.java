package za.co.rockmission.ledger.transaction;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByTxnDateBetweenOrderByTxnDateDescIdDesc(LocalDate from, LocalDate to);
    boolean existsByReversalOfId(Long id);
}
