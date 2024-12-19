package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;

    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    public UserPoint charge(final Long userId, Long amount) {
        long addAmount = addAmount(amount);
        UserPoint userPoint = userPointTable.selectById(userId);
        return updatedPoint(userId, userPoint.point() + addAmount, TransactionType.CHARGE);
    }

    public long addAmount(final long amount) {
        if (amount < 10_000) {
            throw new IllegalStateException("최소 10_000원 이상 충전 가능합니다");
        }
        if (amount % 10_000 != 0) {
            throw new IllegalStateException("충전은 10_000원 단위로 가능 합니다");
        }
        if (amount > 100_000) {
            throw new IllegalStateException("충전은 100_000원 이하로 가능 합니다");
        }
        return amount;
    }

    public UserPoint use(final Long userId, final Long amount) {
        long useAmount = useAmount(amount);
        UserPoint userPoint = userPointTable.selectById(userId);
        return updatedPoint(userId, userPoint.point() - useAmount, TransactionType.USE);
    }

    public long useAmount(final long amount) {
        if(amount < 0) {
            throw new IllegalStateException("사용금액은 0원 이상이여야 합니다");
        }
        return amount;
    }

    public UserPoint select(final Long userId) {
        return userPointTable.selectById(userId);
    }

    public UserPoint updatedPoint(final Long userId, final Long amount, TransactionType type) {

        long updatedPoint = updatedPoint(amount);

        UserPoint updatedUserPoint = userPointTable.insertOrUpdate(userId, updatedPoint);

        // 히스토리 기록
        pointHistoryTable.insert(userId, updatedPoint, type, System.currentTimeMillis());

        return updatedUserPoint;
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
