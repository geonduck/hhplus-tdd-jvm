package io.hhplus.tdd.point;

import io.hhplus.tdd.database.PointHistoryTable;
import io.hhplus.tdd.database.UserPointTable;
import org.springframework.stereotype.Service;

@Service
public class PointService {

    private final UserPointTable userPointTable;
    private final PointHistoryTable pointHistoryTable;

    public PointService(UserPointTable userPointTable, PointHistoryTable pointHistoryTable) {
        this.userPointTable = userPointTable;
        this.pointHistoryTable = pointHistoryTable;
    }

    public UserPoint charge(final Long userId, Long amount) {
        amount = checkAmount(amount);
        UserPoint userPoint = userPointTable.selectById(userId);
        return updatedPoint(userId, userPoint.point() + amount, TransactionType.CHARGE);
    }

    public long checkAmount(final long amount) {
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

    public UserPoint updatedPoint(final Long userId, final Long updatedPoint, TransactionType type) {

        if (updatedPoint > 10_000_000L) {
            throw new IllegalStateException("최대 10_000_000포인트까지 보유 가능합니다");
        }

        UserPoint updatedUserPoint = userPointTable.insertOrUpdate(userId, updatedPoint);

        // 히스토리 기록
        pointHistoryTable.insert(userId, updatedPoint, type, System.currentTimeMillis());

        return updatedUserPoint;
    }

}