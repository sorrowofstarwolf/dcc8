package com.dcc.web;

import com.dcc.model.EncryptRequest;
import com.dcc.service.EncryptService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class EncryptController {
    private final EncryptService service;

    public EncryptController(EncryptService service) {
        this.service = service;
    }

    @PostMapping("/encrypt")
    public Map<String, String> encrypt(@RequestBody EncryptRequest request) {
        service.submit(request);
        Map<String, String> response = new HashMap<>(2);
        response.put("status", "ACCEPTED");
        response.put("requestId", request.getRequestId());
        return response;
    }
}
