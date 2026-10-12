package com.yukisoffd.lyracode.workspace

import org.junit.Assert.*
import org.junit.Test

class GrantedWorkspacePathTest {
    @Test fun grantsCoverUnicodeChildrenAndLegacyVolumeRoots() {
        assertEquals(emptyList<String>(),grantedDirectorySegments("primary:Documents","primary:Documents"))
        assertEquals(listOf("项目","nested"),grantedDirectorySegments("primary:Documents","primary:Documents/项目/nested"))
        assertEquals(listOf("Documents","项目"),grantedDirectorySegments("primary:","primary:Documents/项目"))
        assertEquals(listOf("source"),grantedDirectorySegments("1234-5678:Projects","1234-5678:Projects/source"))
    }
    @Test fun rejectsSiblingVolumesAndTraversal() {
        for (id in listOf("primary:Documents-other", "primary:Documents/../private", "primary:Documents/./source", "primary:Documents//source", "other:Documents/source")) {
            assertThrows(id,IllegalArgumentException::class.java) { grantedDirectorySegments("primary:Documents",id) }
        }
    }
}
