package com.dcc.domain;

public final class FieldId {
    public static final int USER_ID = 0;
    public static final int SERIAL_NO = 1;
    public static final int USER_CODE = 2;
    public static final int BUSINESS_KEY = 3;
    public static final int ID_CARD = 4;
    public static final int PHONE = 5;
    public static final int NAME = 6;
    public static final int EMAIL = 7;
    public static final int DEVICE_ID = 8;
    public static final int TRANS_ID = 9;
    public static final int SECRET_CODE = 10;
    public static final int FIELD_COUNT = 11;
    public static final int MAX_REQUEST_FIELDS = FIELD_COUNT;

    private FieldId() {
    }

    public static int fromName(String name) {
        switch (name) {
            case "user_id": return USER_ID;
            case "serial_no": return SERIAL_NO;
            case "user_code": return USER_CODE;
            case "business_key": return BUSINESS_KEY;
            case "id_card": return ID_CARD;
            case "phone": return PHONE;
            case "name": return NAME;
            case "email": return EMAIL;
            case "device_id": return DEVICE_ID;
            case "trans_id": return TRANS_ID;
            case "secret_code": return SECRET_CODE;
            default: throw new IllegalArgumentException("Unknown field: " + name);
        }
    }

    public static boolean isMaskField(int fieldId) {
        return fieldId == ID_CARD || fieldId == PHONE || fieldId == NAME || fieldId == EMAIL;
    }

    public static boolean shouldCache(int fieldId) {
        return fieldId == USER_ID;
    }
}
