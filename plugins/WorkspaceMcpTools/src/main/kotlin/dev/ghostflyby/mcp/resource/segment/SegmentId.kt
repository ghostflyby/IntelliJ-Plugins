/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.resource.segment

import dev.ghostflyby.mcp.resource.segment.SegmentId.Companion.next
import java.util.concurrent.atomic.AtomicLong

/**
 * Globally unique identifier for a resource tree segment.
 *
 * Created via [SegmentId.next] with an atomic counter. Equality and hashing
 * are based on the underlying [id], so two [SegmentId] instances created by
 * separate [next] calls are never equal.
 */
@ConsistentCopyVisibility
internal data class SegmentId private constructor(val id: Long) {
    companion object {
        private val counter = AtomicLong(0)

        /** Returns the next unique [SegmentId]. */
        fun next(): SegmentId = SegmentId(counter.incrementAndGet())
    }
}

