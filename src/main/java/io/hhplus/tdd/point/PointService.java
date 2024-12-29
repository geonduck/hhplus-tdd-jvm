package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.apache.catalina.User;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class PointService {

    /*
     * ExcutorService 는 Bean 으로 등록해서 사용하는 것이 더 좋을 것 같아요!
     * 요청별로 비동기 스레드를 열고, 기다리는 방식을 활용했는데 요청스레드는 단순 대기하게됨. ( 비효율 )
     * addAmount 와 같은 함수는 차라리 UserPoint에게 주기?
     * 기존 구현함수들의 책임만 리팩토링이 좀 더 되면 좋을 것 같아요
     * 전체적인 품질 생각하기. 테스트 코드도 리소스니까
     */

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks;
    private final ExecutorService executorService;

    public PointService(
            UserPointTable userPointTable,
            PointHistoryTable pointHistoryTable,
            ExecutorService executorService
    ) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
        this.userLocks = new ConcurrentHashMap<>();
        this.executorService = executorService;
    }

    // 작업 실행 (글로벌 스레드 풀과 사용자별 락 활용)
    private <T> CompletableFuture<T> submitTask(Long userId, Supplier<T> task) {
        ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock(true));
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
                UserPoint user = this.select(userId);
                long addAmount = user.calculateChargeAmount(amount);
                return updatedPoint(userId, addAmount);
            }).get();
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

    public UserPoint use(final Long userId, final Long amount) {
        TransactionType type = TransactionType.USE;
        try {
            return submitTask(userId, () -> {
                UserPoint user = this.select(userId);
                long useAmount = user.calculateUseAmount(amount);
                return updatedPoint(userId, useAmount);
            }).get();
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

    public UserPoint select(final Long userId) {
        return userPointTable.selectById(userId);
    }

    public UserPoint updatedPoint(final Long userId, final Long amount) {
        return userPointTable.insertOrUpdate(userId, amount);
    }

    public void insertHistory(final long userId, final long amount, final TransactionType type){
        pointHistoryTable.insert(userId, amount, type, System.currentTimeMillis());
    }

    public List<PointHistory> getPointHistories(Long userId) {
        List<PointHistory> histories = pointHistoryTable.selectAllByUserId(userId);
        if (histories == null || histories.isEmpty()) {
            return Collections.emptyList(); // null 대신 빈 리스트 반환
        }
        return histories;
    }
}
