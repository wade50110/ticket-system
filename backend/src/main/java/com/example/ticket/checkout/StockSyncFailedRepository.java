package com.example.ticket.checkout;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockSyncFailedRepository extends JpaRepository<StockSyncFailed, Long> {

    List<StockSyncFailed> findTop100ByResolvedAtIsNullOrderByIdAsc();

    /**
     * 取單筆並上悲觀寫鎖(SELECT ... FOR UPDATE),讓同一 id 的併發重試序列化。
     * 用途:排程與 admin 手動重試(繞過 @SchedulerLock)可能同時對同一批 id 各開 REQUIRES_NEW 交易,
     * 若只靠 resolvedAt 判斷,REPEATABLE READ 下兩交易各自快照都讀到 null → 重複 decrementStock、DB 庫存被多扣。
     * 上行鎖後,第二個交易會等第一個 commit 再重讀,看到 resolvedAt 已設就 skip。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM StockSyncFailed f WHERE f.id = :id")
    Optional<StockSyncFailed> findByIdForUpdate(@Param("id") Long id);
}
