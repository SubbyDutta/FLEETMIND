package com;

import com.fleetmind.tools.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class ToolGrpcService extends ToolServiceGrpc.ToolServiceImplBase {
    private final ReassignService reassignService;
    private final NotifyService notifyService;
    private final OrderStatusService orderStatusService;
    private final DriverOverviewService driverOverviewService;
    @Override
    public void reassignOrder(ReassignRequest req, StreamObserver<ReassignResponse> obs)
    {
        ReassignResponse.Builder resp=ReassignResponse.newBuilder();
        try{
            resp.setSuccess(true).setMessage(reassignService.reassign(req.getOrderId(), req.getNewDriverId(), req.getReason()));
        }catch (ToolRejection e)
        {
            resp.setSuccess(false).setMessage(e.getMessage());
        }
        obs.onNext(resp.build());
        obs.onCompleted();
    }

    @Override
    public void notifyCustomer(NotifyRequest req, StreamObserver<NotifyResponse> obs) {
        NotifyResponse.Builder resp = NotifyResponse.newBuilder();
        try {
            resp.setSuccess(true).setMessage(
                    notifyService.notifyCustomer(req.getOrderId(), req.getMessage(), req.getReason()));
        } catch (ToolRejection e) {
            resp.setSuccess(false).setMessage(e.getMessage());
        }
        obs.onNext(resp.build());
        obs.onCompleted();
    }
    @Override
    public void getOrderStatus(OrderStatusRequest req, StreamObserver<OrderStatusResponse> obs) {
        obs.onNext(orderStatusService.getStatus(req.getOrderId()));
        obs.onCompleted();
    }
    @Override
    public void getDriverOverview(DriverOverviewRequest req, StreamObserver<DriverOverviewResponse> obs) {
        obs.onNext(driverOverviewService.getOverview(req.getDriverId()));
        obs.onCompleted();
    }

}
