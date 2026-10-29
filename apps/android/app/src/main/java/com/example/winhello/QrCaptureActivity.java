package com.example.winhello;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public final class QrCaptureActivity extends CaptureActivity {
    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_qr_capture);
        return findViewById(R.id.zxing_barcode_scanner);
    }
}
