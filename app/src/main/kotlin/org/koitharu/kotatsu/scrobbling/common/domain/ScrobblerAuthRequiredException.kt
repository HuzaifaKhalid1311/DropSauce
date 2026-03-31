package org.haziffe.dropsauce.scrobbling.common.domain

import okio.IOException
import org.haziffe.dropsauce.scrobbling.common.domain.model.ScrobblerService

class ScrobblerAuthRequiredException(
	val scrobbler: ScrobblerService,
) : IOException()
