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

import java.util.Collections;
import java.util.List;

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


    /*
     * get Test
     *
     */

    @Test
    @DisplayName("정상 조회 시 잔고 반환")
    void getPoints_WithValidUser_ShouldReturnBalance()
    {
        // given
        final Long amount = 20_000L;
        final Long userId = 1L;
        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, amount, System.currentTimeMillis()));

        // when & then
        assertThat(pointService.select(userId).point()).isEqualTo(amount);
    }

    @Test
    @DisplayName("신규 유저는 초기 포인트 0")
    void getPoints_NewUser_ShouldReturnZero()
    {
        // given
        final Long userId = 1L;
        when(userPointTable.selectById(userId))
                .thenReturn(UserPoint.empty(userId));

        // when & then
        assertThat(pointService.select(userId).point()).isEqualTo(0);
    }

    /*
     * history Test
     *
     */

    @Test
    @DisplayName("조회 시 히스토리가 없을 경우 빈 리스트 반환")
    void getPointHistories_WhenNoHistory_ShouldReturnEmptyList() {
        // given
        final Long userId = 1L;

        // Mock 설정: 히스토리 결과가 없는 상황
        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(Collections.emptyList());

        // when
        List<PointHistory> histories = pointService.getPointHistories(userId);

        // then
        assertThat(histories).isEmpty(); // 반환된 리스트가 비어 있는지 검증
        verify(pointHistoryTable).selectAllByUserId(userId); // 메서드 호출 검증
    }

    @Test
    @DisplayName("정상 조회 시 내역 반환")
    void getPointHistories_WithValidUser_ShouldReturnHistories()
    {
        // given
        final Long userId = 1L;
        List<PointHistory> mockHistories = List.of(
                new PointHistory(1L, userId, 10_000L, TransactionType.CHARGE, 1_000_000L),
                new PointHistory(2L, userId, 5_000L, TransactionType.USE, 1_000_500L)
        );

        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(mockHistories);

        // when
        List<PointHistory> histories = pointService.getPointHistories(userId);

        // then
        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).amount()).isEqualTo(10_000L);
        assertThat(histories.get(1).amount()).isEqualTo(5_000L);
        verify(pointHistoryTable).selectAllByUserId(userId);
    }

    @Test
    @DisplayName("충전 성공 기록 하기")
    void charge_WhenValidAmount_ShouldRecordHistory()
    {
        // given
        final Long userId = 1L;
        final Long amount = 20_000L;
        final Long existingPoint = 50_000L;

        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));

        when(userPointTable.insertOrUpdate(userId, existingPoint + amount))
                .thenReturn(new UserPoint(userId, existingPoint + amount, System.currentTimeMillis()));

        // when
        pointService.charge(userId, amount);

        // then
        verify(userPointTable).insertOrUpdate(userId, existingPoint + amount);
        verify(pointHistoryTable).insert(anyLong(), anyLong(), eq(TransactionType.CHARGE), anyLong());
    }

    @Test
    @DisplayName("사용 성공 기록 하기")
    void use_WhenValidAmount_ShouldRecordHistory()
    {
        // given
        final Long userId = 1L;
        final Long amount = 20_000L;
        final Long existingPoint = 30_000L;

        when(userPointTable.selectById(userId))
                .thenReturn(new UserPoint(userId, existingPoint, System.currentTimeMillis()));

        when(userPointTable.insertOrUpdate(userId, existingPoint - amount))
                .thenReturn(new UserPoint(userId, existingPoint - amount, System.currentTimeMillis()));

        // when
        pointService.use(userId, amount);

        // then
        verify(userPointTable).insertOrUpdate(userId, existingPoint - amount);
        verify(pointHistoryTable).insert(anyLong(), anyLong(), eq(TransactionType.USE), anyLong());
    }


    /*
     * exception Test
     *
     */

    @Test
    @DisplayName("잘못된 금액 입력 시 예외 발생")
    void charge_WithInvalidAmount_ShouldThrowValidationError()
    {
        // given
        final Long userId = 1L;
        final Long invalidAmount = -1_234L; // 충전 불가능한 금액임

        // when & then
        assertThatCode(() -> pointService.charge(userId, invalidAmount))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최소 10_000원 이상 충전 가능합니다");
    }

    @Test
    @DisplayName("음수 금액 사용 시 예외 발생")
    void use_WhenAmountIsNegative_ShouldThrowValidationError()
    {
        // given
        final Long amount = -20_000L;
        final Long userId = 1L;

        // when & then
        assertThatCode(() -> pointService.use(userId, amount)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용금액은 0원 이상이여야 합니다");

    }

    @Test
    @DisplayName("조회 시 히스토리가 null일 경우 빈 리스트 반환")
    void getPointHistories_WhenNullReturned_ShouldReturnEmptyList() {
        // given
        final Long userId = 1L;

        // Mock 설정: 히스토리 결과가 null인 상황
        when(pointHistoryTable.selectAllByUserId(userId)).thenReturn(null);

        // when
        List<PointHistory> histories = pointService.getPointHistories(userId);

        // then
        assertThat(histories).isEmpty(); // 반환된 리스트가 비어 있는지 검증
        verify(pointHistoryTable).selectAllByUserId(userId); // 메서드 호출 검증
    }

}
