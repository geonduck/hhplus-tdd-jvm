package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    // 가짜 만들기 (mock)
    @Mock
    private UserPointTable userPointTable;

    @Mock
    private PointHistoryTable pointHistoryTable;

    @InjectMocks
    private PointService pointService;


    /*
     * charge Test
     *
     */

    @Test
    @DisplayName("최대 잔고 초과 시 실패")
    void charge_WhenAmountExceedsMaxBalance_ShouldThrowException()
    {
        // given
        final Long amount = 10_000L;
        final Long userId = 1L;
        final Long existingPoint = 9_999_999L;

        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));

        // when & then
        assertThatCode(() -> pointService.charge(userId, amount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최대 10_000_000포인트까지 보유 가능합니다");
    }

    @Test
    @DisplayName("최소 충전금액 미달 시 실패")
    void charge_WhenAmountBelowMinimum_ShouldThrowException()
    {
        // given
        final Long amount = 1000L;
        final Long userId = 1L;
        // when & then
        assertThatCode(() -> pointService.charge(userId, amount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최소 10_000원 이상 충전 가능합니다");
    }

    @Test
    @DisplayName("10,000 단위가 아니면 실패")
    void charge_WhenAmountIsNotMultipleOf10K_ShouldThrowException()
    {
        // given
        final Long amount = 12_000L;
        final Long userId = 1L;
        // when & then
        assertThatCode(() -> pointService.charge(userId, amount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("충전은 10_000원 단위로 가능 합니다");
    }

    @Test
    @DisplayName("최대 충전 금액 초과 시 실패")
    void charge_WhenAmountExceedsMaxLimit_ShouldThrowException()
    {
        // given
        final Long amount = 200_000L;
        final Long userId = 1L;
        // when & then
        assertThatCode(() -> pointService.charge(userId, amount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("충전은 100_000원 이하로 가능 합니다");
    }

    @Test
    @DisplayName("정상 충전 시 잔고 증가")
    void charge_WhenConcurrentRequests_ShouldProcessInOrder()
    {
        // given
        final Long amount = 20_000L;
        final Long userId = 1L;
        final Long existingPoint = 99_999L;
        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));

        when(userPointTable.insertOrUpdate(anyLong(), anyLong()))
                .thenReturn(new UserPoint(1L, existingPoint + amount, 1003000));
        // when & then
        assertThat(pointService.charge(userId, amount).point()).isEqualTo(existingPoint + amount);
    }


    /*
     * use Test
     *
     */

    @Test
    @DisplayName("잔고 부족 시 실패")
    void use_WhenBalanceIsInsufficient_ShouldThrowException()
    {
        // given
        final Long amount = 200_000L;
        final Long userId = 1L;
        final Long existingPoint = 99_999L;
        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));
        // when & then
        assertThatCode(() -> pointService.use(userId, amount)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잔고가 부족하여 사용이 불가능 합니다");
    }

    @Test
    @DisplayName("잔고 충분 시 포인트 사용 성공")
    void use_WithSufficientBalance_ShouldDeductBalance()
    {
        // given
        final Long amount = 20_000L;
        final Long userId = 1L;
        final Long existingPoint = 99_999L;
        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));

        when(userPointTable.insertOrUpdate(anyLong(), anyLong()))
                .thenReturn(new UserPoint(1L, amount - existingPoint, 1003000));
        // when & then
        Assertions.assertEquals(amount - existingPoint, pointService.use(userId, amount).point());
    }

    @Test
    @DisplayName("잔고와 동일한 금액 사용 시 잔고 0")
    void use_WithExactBalance_ShouldSetBalanceToZero()
    {
        // given
        final Long amount = 200_000L;
        final Long userId = 1L;
        final Long existingPoint = 200_000L;
        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));
        when(userPointTable.insertOrUpdate(anyLong(), anyLong()))
                .thenReturn(new UserPoint(1L, amount - existingPoint, 1003000));
        // when
        long userPoint = pointService.use(userId, amount).point();
        // then
        assertThat(userPoint).isEqualTo(0);
    }
}
