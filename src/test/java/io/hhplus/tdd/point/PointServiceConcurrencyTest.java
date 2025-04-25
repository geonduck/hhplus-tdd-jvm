package io.hhplus.tdd.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest
class PointServiceConcurrencyTest {

    @Autowired
    private PointService pointService;

    private static final Long VALID_CHARGE_AMOUNT = 10_000L;
    private static final Long VALID_USE_AMOUNT = 5_000L;
    private static final int THREAD_COUNT = 10;

    /*
    * latch.await() 보다 CompletableFuture 를 활용하면, 끝나기를 대기할 수 있어 용이해요 ( 시간 내에 안끝나면 테스트도 실패해요! )
    */

    @Test
    @DisplayName("동시에 여러 번 충전 시 모든 충전이 정상 처리되어야 함")
    void charge_WhenMultipleConcurrentCharges_ShouldProcessAllCharges() throws InterruptedException {
        // given
        final Long userId = 1L;
        final Duration timeout = Duration.ofSeconds(10);

        // when
        List<CompletableFuture<UserPoint>> futures = IntStream.range(0, THREAD_COUNT)
                .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                        pointService.charge(userId, VALID_CHARGE_AMOUNT)
                ))
                .collect(Collectors.toList());

        // then
        assertTimeoutPreemptively(timeout, () -> {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .join();
        });

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
    void use_WhenMultipleConcurrentUses_ShouldNotExceedBalance() {
        // given
        final Long userId = 1L;
        final long initialBalance = VALID_CHARGE_AMOUNT * 5; // 50,000원 초기 충전
        final Duration timeout = Duration.ofSeconds(10);
        pointService.charge(userId, initialBalance);

        // when
        List<CompletableFuture<Void>> futures = IntStream.range(0, THREAD_COUNT)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        pointService.use(userId, VALID_USE_AMOUNT);
                    } catch (IllegalStateException e) {
                        // 잔액 부족 예외는 예상된 동작
                    }
                }))
                .collect(Collectors.toList());

        // then
        assertTimeoutPreemptively(timeout, () ->
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()
        );

        long finalPoint = pointService.select(userId).point();
        List<PointHistory> histories = pointService.getPointHistories(userId);

        assertThat(finalPoint).isGreaterThanOrEqualTo(0);
        assertThat(histories)
                .filteredOn(h -> h.type() == TransactionType.USE)
                .hasSizeLessThanOrEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("충전과 사용이 동시에 발생할 때 데이터 정합성 유지")
    void mixedOperations_WhenConcurrent_ShouldMaintainDataConsistency() {
        // given
        final Long userId = 1L;
        final Duration timeout = Duration.ofSeconds(10);

        // when
        List<CompletableFuture<Void>> futures = IntStream.range(0, THREAD_COUNT)
                .flatMap(i -> IntStream.of(i, i)) // 각 i에 대해 두 개의 작업 생성
                .mapToObj(i -> {
                    if (i % 2 == 0) {
                        return CompletableFuture.runAsync(() ->
                                pointService.charge(userId, VALID_CHARGE_AMOUNT));
                    } else {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                pointService.use(userId, VALID_USE_AMOUNT);
                            } catch (IllegalStateException e) {
                                // 잔액 부족 예외는 예상된 동작
                            }
                        });
                    }
                })
                .collect(Collectors.toList());

        // then
        assertTimeoutPreemptively(timeout, () ->
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()
        );

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
    void failedTransactions_ShouldBeRecordedInHistory() {
        // given
        final Long userId = 1L;
        final Long invalidAmount = 5_000L; // 최소 충전금액 미달
        final Duration timeout = Duration.ofSeconds(10);

        // when
        List<CompletableFuture<Void>> futures = IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        pointService.charge(userId, invalidAmount);
                    } catch (IllegalStateException e) {
                        // 예상된 예외
                    }
                }))
                .collect(Collectors.toList());

        // then
        assertTimeoutPreemptively(timeout, () ->
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()
        );

        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories)
                .isNotEmpty()
                .anyMatch(h -> h.type() == TransactionType.FAIL);
    }

    @Test
    @DisplayName("대량의 동시 요청 처리 성능 테스트")
    void massiveConcurrentRequests_ShouldHandleEfficiently() {
        // given
        final Long userId = 1L;
        final int massiveThreadCount = 100;
        final Duration timeout = Duration.ofSeconds(30);
        long startTime = System.currentTimeMillis();

        // when
        List<CompletableFuture<Void>> futures = IntStream.range(0, massiveThreadCount)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        if (i % 2 == 0) {
                            pointService.charge(userId, VALID_CHARGE_AMOUNT);
                        } else {
                            pointService.use(userId, VALID_USE_AMOUNT);
                        }
                    } catch (IllegalStateException e) {
                        // 예상된 예외
                    }
                }))
                .collect(Collectors.toList());

        // then
        assertTimeoutPreemptively(timeout, () ->
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()
        );

        long duration = System.currentTimeMillis() - startTime;
        assertThat(duration).isLessThan(30000);

        List<PointHistory> histories = pointService.getPointHistories(userId);
        assertThat(histories).hasSizeGreaterThanOrEqualTo(massiveThreadCount / 2);
    }

    @Test
    @DisplayName("서로 다른 사용자의 동시 요청 테스트")
    void differentUsers_ShouldProcessConcurrently() {
        // given
        final Long user1 = 1L;
        final Long user2 = 2L;
        final Duration timeout = Duration.ofSeconds(5);

        // when
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // user1의 10개 요청
        IntStream.range(0, 10).forEach(i ->
                futures.add(CompletableFuture.runAsync(() ->
                        pointService.charge(user1, VALID_CHARGE_AMOUNT))));

        // user2의 10개 요청
        IntStream.range(0, 10).forEach(i ->
                futures.add(CompletableFuture.runAsync(() ->
                        pointService.charge(user2, VALID_CHARGE_AMOUNT))));

        // then
        assertTimeoutPreemptively(timeout, () ->
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join()
        );

        // 각 사용자의 최종 포인트 확인
        assertThat(pointService.select(user1).point()).isEqualTo(VALID_CHARGE_AMOUNT * 10);
        assertThat(pointService.select(user2).point()).isEqualTo(VALID_CHARGE_AMOUNT * 10);
    }
}