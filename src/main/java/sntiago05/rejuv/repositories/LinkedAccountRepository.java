package sntiago05.rejuv.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import sntiago05.rejuv.domain.LinkedAccount;

public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> {
}
