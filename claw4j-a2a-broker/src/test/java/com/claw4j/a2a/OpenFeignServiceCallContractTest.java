package com.claw4j.a2a;

import com.claw4j.common.dto.ApiResponse;
import com.claw4j.common.dto.InternalServiceStatus;
import com.claw4j.common.exception.BusinessException;
import com.claw4j.common.exception.ErrorCode;
import com.claw4j.a2a.controller.A2aInternalCallController;
import com.claw4j.a2a.service.A2aBrokerStatusService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies A2A Broker's provider-side internal call contract.
 */
class OpenFeignServiceCallContractTest {

    @Test
    void providerServiceReturnsIdentityAndRejectsMissingContext() {
        A2aBrokerStatusService service = new A2aBrokerStatusService("claw4j-a2a-broker", "a2a-broker", 8084);

        ApiResponse<InternalServiceStatus> valid = new A2aInternalCallController(service)
                .getStatus("req-1", "tenant-1", "user-1", "idem-1");

        assertThat(valid.getData().getServiceName()).isEqualTo("claw4j-a2a-broker");
        assertThat(valid.getData().getRole()).isEqualTo("a2a-broker");
        assertThat(valid.getData().getInstancePort()).isEqualTo(8084);
        assertThat(valid.getData().getRequestId()).isEqualTo("req-1");
        assertThatThrownBy(() -> service.getStatus("req-1", "", "user-1", "idem-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
