package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();
    private final ExecutorService executorService;


    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
        // CPU 코어 수를 기반으로 스레드 풀 크기 설정
        this.executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors()
        );
    }

    // 작업 실행 (글로벌 스레드 풀과 사용자별 락 활용)
    private <T> CompletableFuture<T> submitTask(Long userId, Supplier<T> task) {
        ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
        return CompletableFuture.supplyAsync(() -> {
            lock.lock(); // 락 획득
            try {
                return task.get();
            } catch (Exception e) {
                // 작업 내부에서 발생한 예외 처리
                throw new IllegalStateException(e.getMessage());
            } finally {
                lock.unlock(); // 락 해제
            }
        }, executorService);
    }

    public UserPoint charge(final Long userId, final Long amount) {
        TransactionType type = TransactionType.CHARGE;
        try {
            return submitTask(userId, () -> {
                long addAmount = addAmount(userId, amount);
                return updatedPoint(userId, addAmount);
            }).get(); // Future의 결과를 기다려 반환
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
            throw new RuntimeException("작업이 중단되었습니다.", e);
        } catch (IllegalStateException | ExecutionException e) {
            type = TransactionType.FAIL;
            throw new IllegalStateException(e.getMessage());
        } finally {
            insertHistory(userId, amount, type);
        }
    }

    public long addAmount(final Long userId, final long amount) {
        if (amount < 10_000) {
            throw new IllegalStateException("최소 10_000원 이상 충전 가능합니다");
        }
        if (amount % 10_000 != 0) {
            throw new IllegalStateException("충전은 10_000원 단위로 가능 합니다");
        }
        if (amount > 100_000) {
            throw new IllegalStateException("충전은 100_000원 이하로 가능 합니다");
        }

        return updatedPoint(this.select(userId).point() + amount);
    }

    public UserPoint use(final Long userId, final Long amount) {
        TransactionType type = TransactionType.USE;
        try {
            return submitTask(userId, () -> {
                long useAmount = useAmount(userId, amount);
                return updatedPoint(userId, useAmount);
            }).get(); // Future의 결과를 기다려 반환
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
            throw new RuntimeException("작업이 중단되었습니다.", e);
        } catch (IllegalStateException | ExecutionException e) {
            type = TransactionType.FAIL;
            throw new IllegalStateException(e.getMessage());
        } finally {
            insertHistory(userId, amount, type);
        }
    }

    public long useAmount(final Long userId, final long amount) {
        if(amount < 0) {
            throw new IllegalStateException("사용금액은 0원 이상이여야 합니다");
        }
        return updatedPoint(this.select(userId).point() - amount);
    }

    public UserPoint select(final Long userId) {
        return userPointTable.selectById(userId);
    }

    public UserPoint updatedPoint(final Long userId, final Long amount) {
        return userPointTable.insertOrUpdate(userId, amount);
    }

    public void insertHistory(final long userId, final long amount, final TransactionType type){
        pointHistoryTable.insert(userId, amount, type, System.currentTimeMillis());
    }

    public long updatedPoint(final long updatedPoint) {
        if (updatedPoint > 10_000_000L) {
            throw new IllegalStateException("최대 10_000_000포인트까지 보유 가능합니다");
        }
        if (updatedPoint < 0) {
            throw new IllegalStateException("잔고가 부족하여 사용이 불가능 합니다");
        }
        return updatedPoint;
    }

    public List<PointHistory> getPointHistories(Long userId) {
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(userId);
        if (histories == null || histories.isEmpty()) {
            return Collections.emptyList(); // null 대신 빈 리스트 반환
        }
        return histories;
    }
}
