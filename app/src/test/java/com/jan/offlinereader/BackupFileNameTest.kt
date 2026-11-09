package com.jan.offlinereader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BackupFileNameTest {
    @Test fun nameIncludesZeroPaddedDate() {
        assertEquals("Offline-Reader-library-2026-11-09.zip", backupFileName(LocalDate.of(2026, 11, 9)))
        assertEquals("Offline-Reader-library-2027-01-31.zip", backupFileName(LocalDate.of(2027, 1, 31)))
    }
}
