package io.hhplus.tdd.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PointServiceConcurrencyTest {

    @Autowired
    private PointService pointService;

    private static final Long VALID_CHARGE_AMOUNT = 10_000L;
    private static final Long VALID_USE_AMOUNT = 5_000L;
    private static final int THREAD_COUNT = 10;

    @Test
    @DisplayName("동시에 여러 번 충전 시 모든 충전이 정상 처리되어야 함")
    void charge_WhenMultipleConcurrentCharges_ShouldProcessAllCharges() throws InterruptedException {
        // given
        final Long userId = 1L;
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // when
        IntStream.range(0, THREAD_COUNT).forEach(i ->
                executorService.submit(() -> {
                    try {
                        pointService.charge(userId, VALID_CHARGE_AMOUNT);
                    } finally {
                        latch.countDown();
                    }
                })
        );

        // then
        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // 최종 포인트 확인
        long finalPoint = pointService.select(userId).point();
        assertThat(finalPoint).isEqualTo(VALID_CHARGE_AMOUNT * THREAD_COUNT);

        // 히스토리 확인
        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories)
                .hasSize(THREAD_COUNT)
                .allMatch(h -> h.type() == TransactionType.CHARGE || h.type() == TransactionType.FAIL);
    }

    @Test
    @DisplayName("동시에 여러 번 사용 시 잔액 초과되지 않아야 함")
    void use_WhenMultipleConcurrentUses_ShouldNotExceedBalance() throws InterruptedException {
        // given
        final Long userId = 1L;
        final long initialBalance = VALID_CHARGE_AMOUNT * 5; // 50,000원 초기 충전
        pointService.charge(userId, initialBalance);

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // when
        IntStream.range(0, THREAD_COUNT).forEach(i ->
                executorService.submit(() -> {
                    try {
                        pointService.use(userId, VALID_USE_AMOUNT);
                    } catch (IllegalStateException e) {
                        // 잔액 부족 예외는 예상된 동작
                    } finally {
                        latch.countDown();
                    }
                })
        );

        // then
        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        long finalPoint = pointService.select(userId).point();
        List<PointHistory> histories = pointService.getPointHistories(userId);

        assertThat(finalPoint).isGreaterThanOrEqualTo(0);
        assertThat(histories)
                .filteredOn(h -> h.type() == TransactionType.USE)
                .hasSizeLessThanOrEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("충전과 사용이 동시에 발생할 때 데이터 정합성 유지")
    void mixedOperations_WhenConcurrent_ShouldMaintainDataConsistency() throws InterruptedException {
        // given
        final Long userId = 1L;
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT * 2);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT * 2);

        // when
        IntStream.range(0, THREAD_COUNT).forEach(i -> {
            // 충전 작업
            executorService.submit(() -> {
                try {
                    pointService.charge(userId, VALID_CHARGE_AMOUNT);
                } finally {
                    latch.countDown();
                }
            });

            // 사용 작업
            executorService.submit(() -> {
                try {
                    pointService.use(userId, VALID_USE_AMOUNT);
                } catch (IllegalStateException e) {
                    // 잔액 부족 예외는 예상된 동작
                } finally {
                    latch.countDown();
                }
            });
        });

        // then
        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        long finalPoint = pointService.select(userId).point();
        List<PointHistory> histories = pointService.getPointHistories(userId);

        assertThat(finalPoint).isGreaterThanOrEqualTo(0);
        assertThat(histories)
                .isNotEmpty()
                .allMatch(h -> h.type() == TransactionType.CHARGE
                        || h.type() == TransactionType.USE
                        || h.type() == TransactionType.FAIL);
    }

    @Test
    @DisplayName("실패한 트랜잭션도 히스토리에 기록되어야 함")
    void failedTransactions_ShouldBeRecordedInHistory() throws InterruptedException {
        // given
        final Long userId = 1L;
        final Long invalidAmount = 5_000L; // 최소 충전금액 미달
        ExecutorService executorService = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        // when
        IntStream.range(0, 5).forEach(i ->
                executorService.submit(() -> {
                    try {
                        pointService.charge(userId, invalidAmount);
                    } catch (IllegalStateException e) {
                        // 예상된 예외
                    } finally {
                        latch.countDown();
                    }
                })
        );

        // then
        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories)
                .isNotEmpty()
                .anyMatch(h -> h.type() == TransactionType.FAIL);
    }

    @Test
    @DisplayName("대량의 동시 요청 처리 성능 테스트")
    void massiveConcurrentRequests_ShouldHandleEfficiently() throws InterruptedException {
        // given
        final Long userId = 1L;
        final int massiveThreadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(massiveThreadCount);
        CountDownLatch latch = new CountDownLatch(massiveThreadCount);
        long startTime = System.currentTimeMillis();

        // when
        IntStream.range(0, massiveThreadCount).forEach(i ->
                executorService.submit(() -> {
                    try {
                        if (i % 2 == 0) {
                            pointService.charge(userId, VALID_CHARGE_AMOUNT);
                        } else {
                            pointService.use(userId, VALID_USE_AMOUNT);
                        }
                    } catch (IllegalStateException e) {
                        // 예상된 예외
                    } finally {
                        latch.countDown();
                    }
                })
        );

        // then
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;
        executorService.shutdown();

        assertThat(completed).isTrue();
        assertThat(duration).isLessThan(30000); // 30초 이내 완료되어야 함

        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories).hasSizeGreaterThanOrEqualTo(massiveThreadCount / 2);
    }

    @Test
    @DisplayName("서로 다른 사용자의 동시 요청 테스트")
    void differentUsers_ShouldProcessConcurrently() throws InterruptedException {
        // given
        final Long user1 = 1L;
        final Long user2 = 2L;
        ExecutorService executorService = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(20);  // 각 사용자당 10개 요청

        // when
        // user1의 10개 요청
        IntStream.range(0, 10).forEach(i ->
                executorService.submit(() -> {
                    try {
                        pointService.charge(user1, VALID_CHARGE_AMOUNT);
                    } finally {
                        latch.countDown();
                    }
                })
        );

        // user2의 10개 요청
        IntStream.range(0, 10).forEach(i ->
                executorService.submit(() -> {
                    try {
                        pointService.charge(user2, VALID_CHARGE_AMOUNT);
                    } finally {
                        latch.countDown();
                    }
                })
        );

        // then
        latch.await(5, TimeUnit.SECONDS);  // 더 짧은 시간으로 설정
        executorService.shutdown();

        // 각 사용자의 최종 포인트 확인
        assertThat(pointService.select(user1).point()).isEqualTo(VALID_CHARGE_AMOUNT * 10);
        assertThat(pointService.select(user2).point()).isEqualTo(VALID_CHARGE_AMOUNT * 10);
    }
}