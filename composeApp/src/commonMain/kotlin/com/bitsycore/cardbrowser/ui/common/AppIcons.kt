package com.bitsycore.cardbrowser.ui.common

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons this app uses that `material-icons-core` does not carry.
 *
 * ## Why these are here rather than depended on
 *
 * `material-icons-extended` is every Material icon there is -- a **36 MB** desktop jar and an
 * **18 MB** klib for each iOS target -- and this app draws 24 of them. R8 strips the rest from an
 * Android release build; nothing strips them from a desktop distributable or an iOS binary, which
 * is where the weight was being paid.
 *
 * ## Where they came from
 *
 * Generated from that library's own sources jar by `composeApp/tools/genicons.py`, so the path data is exactly
 * what it would have drawn -- not redrawn by hand and not re-exported from Material Symbols, either
 * of which risks a shape that differs by a pixel from the thirteen icons still coming from
 * `material-icons-core`. Those thirteen are still `Icons.Outlined.*`; mixing the two sources is
 * fine precisely because both are the same drawings.
 *
 * Apache 2.0, © The Android Open Source Project, like the library they come from.
 *
 * ## Adding one
 *
 * Add its name to `WANTED` in the generator and re-run it against the sources jar. Do not paste
 * path data in by hand.
 */
object AppIcons {

	val ArrowDownward: ImageVector
		get() {
			if (_arrowDownward != null) {
				return _arrowDownward!!
			}
			_arrowDownward = materialIcon(name = "AppIcons.ArrowDownward") {
				materialPath {
					moveTo(20.0f, 12.0f)
					lineToRelative(-1.41f, -1.41f)
					lineTo(13.0f, 16.17f)
					verticalLineTo(4.0f)
					horizontalLineToRelative(-2.0f)
					verticalLineToRelative(12.17f)
					lineToRelative(-5.58f, -5.59f)
					lineTo(4.0f, 12.0f)
					lineToRelative(8.0f, 8.0f)
					lineToRelative(8.0f, -8.0f)
					close()
				}
			}
			return _arrowDownward!!
		}

	private var _arrowDownward: ImageVector? = null

	val ArrowUpward: ImageVector
		get() {
			if (_arrowUpward != null) {
				return _arrowUpward!!
			}
			_arrowUpward = materialIcon(name = "AppIcons.ArrowUpward") {
				materialPath {
					moveTo(4.0f, 12.0f)
					lineToRelative(1.41f, 1.41f)
					lineTo(11.0f, 7.83f)
					verticalLineTo(20.0f)
					horizontalLineToRelative(2.0f)
					verticalLineTo(7.83f)
					lineToRelative(5.58f, 5.59f)
					lineTo(20.0f, 12.0f)
					lineToRelative(-8.0f, -8.0f)
					lineToRelative(-8.0f, 8.0f)
					close()
				}
			}
			return _arrowUpward!!
		}

	private var _arrowUpward: ImageVector? = null

	val BrokenImage: ImageVector
		get() {
			if (_brokenImage != null) {
				return _brokenImage!!
			}
			_brokenImage = materialIcon(name = "AppIcons.BrokenImage") {
				materialPath {
					moveTo(19.0f, 3.0f)
					lineTo(5.0f, 3.0f)
					curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
					verticalLineToRelative(14.0f)
					curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
					horizontalLineToRelative(14.0f)
					curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
					lineTo(21.0f, 5.0f)
					curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
					close()
					moveTo(19.0f, 19.0f)
					lineTo(5.0f, 19.0f)
					verticalLineToRelative(-4.58f)
					lineToRelative(0.99f, 0.99f)
					lineToRelative(4.0f, -4.0f)
					lineToRelative(4.0f, 4.0f)
					lineToRelative(4.0f, -3.99f)
					lineTo(19.0f, 12.43f)
					lineTo(19.0f, 19.0f)
					close()
					moveTo(19.0f, 9.59f)
					lineToRelative(-1.01f, -1.01f)
					lineToRelative(-4.0f, 4.01f)
					lineToRelative(-4.0f, -4.0f)
					lineToRelative(-4.0f, 4.0f)
					lineToRelative(-0.99f, -1.0f)
					lineTo(5.0f, 5.0f)
					horizontalLineToRelative(14.0f)
					verticalLineToRelative(4.59f)
					close()
				}
			}
			return _brokenImage!!
		}

	private var _brokenImage: ImageVector? = null

	val ChevronLeft: ImageVector
		get() {
			if (_chevronLeft != null) {
				return _chevronLeft!!
			}
			_chevronLeft = materialIcon(name = "AppIcons.ChevronLeft") {
				materialPath {
					moveTo(15.41f, 7.41f)
					lineTo(14.0f, 6.0f)
					lineToRelative(-6.0f, 6.0f)
					lineToRelative(6.0f, 6.0f)
					lineToRelative(1.41f, -1.41f)
					lineTo(10.83f, 12.0f)
					lineToRelative(4.58f, -4.59f)
					close()
				}
			}
			return _chevronLeft!!
		}

	private var _chevronLeft: ImageVector? = null

	val ChevronRight: ImageVector
		get() {
			if (_chevronRight != null) {
				return _chevronRight!!
			}
			_chevronRight = materialIcon(name = "AppIcons.ChevronRight") {
				materialPath {
					moveTo(10.0f, 6.0f)
					lineTo(8.59f, 7.41f)
					lineTo(13.17f, 12.0f)
					lineToRelative(-4.58f, 4.59f)
					lineTo(10.0f, 18.0f)
					lineToRelative(6.0f, -6.0f)
					lineToRelative(-6.0f, -6.0f)
					close()
				}
			}
			return _chevronRight!!
		}

	private var _chevronRight: ImageVector? = null

	val CloudDownload: ImageVector
		get() {
			if (_cloudDownload != null) {
				return _cloudDownload!!
			}
			_cloudDownload = materialIcon(name = "AppIcons.CloudDownload") {
				materialPath {
					moveTo(19.35f, 10.04f)
					curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
					curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
					curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
					curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
					horizontalLineToRelative(13.0f)
					curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
					curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
					close()
					moveTo(19.0f, 18.0f)
					lineTo(6.0f, 18.0f)
					curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
					curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
					lineToRelative(1.07f, -0.11f)
					lineToRelative(0.5f, -0.95f)
					curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
					curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
					lineToRelative(0.3f, 1.5f)
					lineToRelative(1.53f, 0.11f)
					curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
					curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
					close()
					moveTo(13.45f, 10.0f)
					horizontalLineToRelative(-2.9f)
					verticalLineToRelative(3.0f)
					lineTo(8.0f, 13.0f)
					lineToRelative(4.0f, 4.0f)
					lineToRelative(4.0f, -4.0f)
					horizontalLineToRelative(-2.55f)
					close()
				}
			}
			return _cloudDownload!!
		}

	private var _cloudDownload: ImageVector? = null

	val CloudOff: ImageVector
		get() {
			if (_cloudOff != null) {
				return _cloudOff!!
			}
			_cloudOff = materialIcon(name = "AppIcons.CloudOff") {
				materialPath {
					moveTo(24.0f, 15.0f)
					curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
					curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
					curveToRelative(-1.33f, 0.0f, -2.57f, 0.36f, -3.65f, 0.97f)
					lineToRelative(1.49f, 1.49f)
					curveTo(10.51f, 6.17f, 11.23f, 6.0f, 12.0f, 6.0f)
					curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
					verticalLineToRelative(0.5f)
					horizontalLineTo(19.0f)
					curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
					curveToRelative(0.0f, 0.99f, -0.48f, 1.85f, -1.21f, 2.4f)
					lineToRelative(1.41f, 1.41f)
					curveToRelative(1.09f, -0.92f, 1.8f, -2.27f, 1.8f, -3.81f)
					close()
					moveTo(4.41f, 3.86f)
					lineTo(3.0f, 5.27f)
					lineToRelative(2.77f, 2.77f)
					horizontalLineToRelative(-0.42f)
					curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
					curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
					horizontalLineToRelative(11.73f)
					lineToRelative(2.0f, 2.0f)
					lineToRelative(1.41f, -1.41f)
					lineTo(4.41f, 3.86f)
					close()
					moveTo(6.0f, 18.0f)
					curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
					reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
					horizontalLineToRelative(1.73f)
					lineToRelative(8.0f, 8.0f)
					horizontalLineTo(6.0f)
					close()
				}
			}
			return _cloudOff!!
		}

	private var _cloudOff: ImageVector? = null

	val Description: ImageVector
		get() {
			if (_description != null) {
				return _description!!
			}
			_description = materialIcon(name = "AppIcons.Description") {
				materialPath {
					moveTo(8.0f, 16.0f)
					horizontalLineToRelative(8.0f)
					verticalLineToRelative(2.0f)
					lineTo(8.0f, 18.0f)
					close()
					moveTo(8.0f, 12.0f)
					horizontalLineToRelative(8.0f)
					verticalLineToRelative(2.0f)
					lineTo(8.0f, 14.0f)
					close()
					moveTo(14.0f, 2.0f)
					lineTo(6.0f, 2.0f)
					curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
					verticalLineToRelative(16.0f)
					curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
					lineTo(18.0f, 22.0f)
					curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
					lineTo(20.0f, 8.0f)
					lineToRelative(-6.0f, -6.0f)
					close()
					moveTo(18.0f, 20.0f)
					lineTo(6.0f, 20.0f)
					lineTo(6.0f, 4.0f)
					horizontalLineToRelative(7.0f)
					verticalLineToRelative(5.0f)
					horizontalLineToRelative(5.0f)
					verticalLineToRelative(11.0f)
					close()
				}
			}
			return _description!!
		}

	private var _description: ImageVector? = null

	val Download: ImageVector
		get() {
			if (_download != null) {
				return _download!!
			}
			_download = materialIcon(name = "AppIcons.Download") {
				materialPath {
					moveTo(19.0f, 9.0f)
					horizontalLineToRelative(-4.0f)
					lineTo(15.0f, 3.0f)
					lineTo(9.0f, 3.0f)
					verticalLineToRelative(6.0f)
					lineTo(5.0f, 9.0f)
					lineToRelative(7.0f, 7.0f)
					lineToRelative(7.0f, -7.0f)
					close()
					moveTo(11.0f, 11.0f)
					lineTo(11.0f, 5.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(6.0f)
					horizontalLineToRelative(1.17f)
					lineTo(12.0f, 13.17f)
					lineTo(9.83f, 11.0f)
					lineTo(11.0f, 11.0f)
					close()
					moveTo(5.0f, 18.0f)
					horizontalLineToRelative(14.0f)
					verticalLineToRelative(2.0f)
					lineTo(5.0f, 20.0f)
					close()
				}
			}
			return _download!!
		}

	private var _download: ImageVector? = null

	val DownloadDone: ImageVector
		get() {
			if (_downloadDone != null) {
				return _downloadDone!!
			}
			_downloadDone = materialIcon(name = "AppIcons.DownloadDone") {
				materialPath {
					moveTo(5.0f, 18.0f)
					horizontalLineToRelative(14.0f)
					verticalLineToRelative(2.0f)
					lineTo(5.0f, 20.0f)
					verticalLineToRelative(-2.0f)
					close()
					moveTo(9.6f, 15.3f)
					lineTo(5.0f, 10.7f)
					lineToRelative(2.0f, -1.9f)
					lineToRelative(2.6f, 2.6f)
					lineTo(17.0f, 4.0f)
					lineToRelative(2.0f, 2.0f)
					lineToRelative(-9.4f, 9.3f)
					close()
				}
			}
			return _downloadDone!!
		}

	private var _downloadDone: ImageVector? = null

	val DragHandle: ImageVector
		get() {
			if (_dragHandle != null) {
				return _dragHandle!!
			}
			_dragHandle = materialIcon(name = "AppIcons.DragHandle") {
				materialPath {
					moveTo(20.0f, 9.0f)
					horizontalLineTo(4.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(16.0f)
					verticalLineTo(9.0f)
					close()
					moveTo(4.0f, 15.0f)
					horizontalLineToRelative(16.0f)
					verticalLineToRelative(-2.0f)
					horizontalLineTo(4.0f)
					verticalLineToRelative(2.0f)
					close()
				}
			}
			return _dragHandle!!
		}

	private var _dragHandle: ImageVector? = null

	val ErrorOutline: ImageVector
		get() {
			if (_errorOutline != null) {
				return _errorOutline!!
			}
			_errorOutline = materialIcon(name = "AppIcons.ErrorOutline") {
				materialPath {
					moveTo(11.0f, 15.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(-2.0f)
					verticalLineToRelative(-2.0f)
					close()
					moveTo(11.0f, 7.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(6.0f)
					horizontalLineToRelative(-2.0f)
					lineTo(11.0f, 7.0f)
					close()
					moveTo(11.99f, 2.0f)
					curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
					reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
					curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
					reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
					close()
					moveTo(12.0f, 20.0f)
					curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
					reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
					reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
					reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
					close()
				}
			}
			return _errorOutline!!
		}

	private var _errorOutline: ImageVector? = null

	val FilterList: ImageVector
		get() {
			if (_filterList != null) {
				return _filterList!!
			}
			_filterList = materialIcon(name = "AppIcons.FilterList") {
				materialPath {
					moveTo(10.0f, 18.0f)
					horizontalLineToRelative(4.0f)
					verticalLineToRelative(-2.0f)
					horizontalLineToRelative(-4.0f)
					verticalLineToRelative(2.0f)
					close()
					moveTo(3.0f, 6.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(18.0f)
					lineTo(21.0f, 6.0f)
					lineTo(3.0f, 6.0f)
					close()
					moveTo(6.0f, 13.0f)
					horizontalLineToRelative(12.0f)
					verticalLineToRelative(-2.0f)
					lineTo(6.0f, 11.0f)
					verticalLineToRelative(2.0f)
					close()
				}
			}
			return _filterList!!
		}

	private var _filterList: ImageVector? = null

	val GridView: ImageVector
		get() {
			if (_gridView != null) {
				return _gridView!!
			}
			_gridView = materialIcon(name = "AppIcons.GridView") {
				materialPath {
					moveTo(3.0f, 3.0f)
					verticalLineToRelative(8.0f)
					horizontalLineToRelative(8.0f)
					verticalLineTo(3.0f)
					horizontalLineTo(3.0f)
					close()
					moveTo(9.0f, 9.0f)
					horizontalLineTo(5.0f)
					verticalLineTo(5.0f)
					horizontalLineToRelative(4.0f)
					verticalLineTo(9.0f)
					close()
					moveTo(3.0f, 13.0f)
					verticalLineToRelative(8.0f)
					horizontalLineToRelative(8.0f)
					verticalLineToRelative(-8.0f)
					horizontalLineTo(3.0f)
					close()
					moveTo(9.0f, 19.0f)
					horizontalLineTo(5.0f)
					verticalLineToRelative(-4.0f)
					horizontalLineToRelative(4.0f)
					verticalLineTo(19.0f)
					close()
					moveTo(13.0f, 3.0f)
					verticalLineToRelative(8.0f)
					horizontalLineToRelative(8.0f)
					verticalLineTo(3.0f)
					horizontalLineTo(13.0f)
					close()
					moveTo(19.0f, 9.0f)
					horizontalLineToRelative(-4.0f)
					verticalLineTo(5.0f)
					horizontalLineToRelative(4.0f)
					verticalLineTo(9.0f)
					close()
					moveTo(13.0f, 13.0f)
					verticalLineToRelative(8.0f)
					horizontalLineToRelative(8.0f)
					verticalLineToRelative(-8.0f)
					horizontalLineTo(13.0f)
					close()
					moveTo(19.0f, 19.0f)
					horizontalLineToRelative(-4.0f)
					verticalLineToRelative(-4.0f)
					horizontalLineToRelative(4.0f)
					verticalLineTo(19.0f)
					close()
				}
			}
			return _gridView!!
		}

	private var _gridView: ImageVector? = null

	val Inbox: ImageVector
		get() {
			if (_inbox != null) {
				return _inbox!!
			}
			_inbox = materialIcon(name = "AppIcons.Inbox") {
				materialPath {
					moveTo(19.0f, 3.0f)
					lineTo(5.0f, 3.0f)
					curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
					verticalLineToRelative(14.0f)
					curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
					horizontalLineToRelative(14.0f)
					curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
					lineTo(21.0f, 5.0f)
					curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
					close()
					moveTo(19.0f, 19.0f)
					lineTo(5.0f, 19.0f)
					verticalLineToRelative(-3.0f)
					horizontalLineToRelative(3.56f)
					curveToRelative(0.69f, 1.19f, 1.97f, 2.0f, 3.45f, 2.0f)
					reflectiveCurveToRelative(2.75f, -0.81f, 3.45f, -2.0f)
					lineTo(19.0f, 16.0f)
					verticalLineToRelative(3.0f)
					close()
					moveTo(19.0f, 14.0f)
					horizontalLineToRelative(-4.99f)
					curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
					reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
					lineTo(5.0f, 14.0f)
					lineTo(5.0f, 5.0f)
					horizontalLineToRelative(14.0f)
					verticalLineToRelative(9.0f)
					close()
				}
			}
			return _inbox!!
		}

	private var _inbox: ImageVector? = null

	val SearchOff: ImageVector
		get() {
			if (_searchOff != null) {
				return _searchOff!!
			}
			_searchOff = materialIcon(name = "AppIcons.SearchOff") {
				materialPath {
					moveTo(15.5f, 14.0f)
					horizontalLineToRelative(-0.79f)
					lineToRelative(-0.28f, -0.27f)
					curveTo(15.41f, 12.59f, 16.0f, 11.11f, 16.0f, 9.5f)
					curveTo(16.0f, 5.91f, 13.09f, 3.0f, 9.5f, 3.0f)
					curveTo(6.08f, 3.0f, 3.28f, 5.64f, 3.03f, 9.0f)
					horizontalLineToRelative(2.02f)
					curveTo(5.3f, 6.75f, 7.18f, 5.0f, 9.5f, 5.0f)
					curveTo(11.99f, 5.0f, 14.0f, 7.01f, 14.0f, 9.5f)
					reflectiveCurveTo(11.99f, 14.0f, 9.5f, 14.0f)
					curveToRelative(-0.17f, 0.0f, -0.33f, -0.03f, -0.5f, -0.05f)
					verticalLineToRelative(2.02f)
					curveTo(9.17f, 15.99f, 9.33f, 16.0f, 9.5f, 16.0f)
					curveToRelative(1.61f, 0.0f, 3.09f, -0.59f, 4.23f, -1.57f)
					lineTo(14.0f, 14.71f)
					verticalLineToRelative(0.79f)
					lineToRelative(5.0f, 4.99f)
					lineTo(20.49f, 19.0f)
					lineTo(15.5f, 14.0f)
					close()
				}
				materialPath {
					moveTo(6.47f, 10.82f)
					lineToRelative(-2.47f, 2.47f)
					lineToRelative(-2.47f, -2.47f)
					lineToRelative(-0.71f, 0.71f)
					lineToRelative(2.47f, 2.47f)
					lineToRelative(-2.47f, 2.47f)
					lineToRelative(0.71f, 0.71f)
					lineToRelative(2.47f, -2.47f)
					lineToRelative(2.47f, 2.47f)
					lineToRelative(0.71f, -0.71f)
					lineToRelative(-2.47f, -2.47f)
					lineToRelative(2.47f, -2.47f)
					close()
				}
			}
			return _searchOff!!
		}

	private var _searchOff: ImageVector? = null

	val StarBorder: ImageVector
		get() {
			if (_starBorder != null) {
				return _starBorder!!
			}
			_starBorder = materialIcon(name = "AppIcons.StarBorder") {
				materialPath {
					moveTo(22.0f, 9.24f)
					lineToRelative(-7.19f, -0.62f)
					lineTo(12.0f, 2.0f)
					lineTo(9.19f, 8.63f)
					lineTo(2.0f, 9.24f)
					lineToRelative(5.46f, 4.73f)
					lineTo(5.82f, 21.0f)
					lineTo(12.0f, 17.27f)
					lineTo(18.18f, 21.0f)
					lineToRelative(-1.63f, -7.03f)
					lineTo(22.0f, 9.24f)
					close()
					moveTo(12.0f, 15.4f)
					lineToRelative(-3.76f, 2.27f)
					lineToRelative(1.0f, -4.28f)
					lineToRelative(-3.32f, -2.88f)
					lineToRelative(4.38f, -0.38f)
					lineTo(12.0f, 6.1f)
					lineToRelative(1.71f, 4.04f)
					lineToRelative(4.38f, 0.38f)
					lineToRelative(-3.32f, 2.88f)
					lineToRelative(1.0f, 4.28f)
					lineTo(12.0f, 15.4f)
					close()
				}
			}
			return _starBorder!!
		}

	private var _starBorder: ImageVector? = null

	val Storage: ImageVector
		get() {
			if (_storage != null) {
				return _storage!!
			}
			_storage = materialIcon(name = "AppIcons.Storage") {
				materialPath {
					moveTo(2.0f, 20.0f)
					horizontalLineToRelative(20.0f)
					verticalLineToRelative(-4.0f)
					lineTo(2.0f, 16.0f)
					verticalLineToRelative(4.0f)
					close()
					moveTo(4.0f, 17.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(2.0f)
					lineTo(4.0f, 19.0f)
					verticalLineToRelative(-2.0f)
					close()
					moveTo(2.0f, 4.0f)
					verticalLineToRelative(4.0f)
					horizontalLineToRelative(20.0f)
					lineTo(22.0f, 4.0f)
					lineTo(2.0f, 4.0f)
					close()
					moveTo(6.0f, 7.0f)
					lineTo(4.0f, 7.0f)
					lineTo(4.0f, 5.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(2.0f)
					close()
					moveTo(2.0f, 14.0f)
					horizontalLineToRelative(20.0f)
					verticalLineToRelative(-4.0f)
					lineTo(2.0f, 10.0f)
					verticalLineToRelative(4.0f)
					close()
					moveTo(4.0f, 11.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(2.0f)
					lineTo(4.0f, 13.0f)
					verticalLineToRelative(-2.0f)
					close()
				}
			}
			return _storage!!
		}

	private var _storage: ImageVector? = null

	val Style: ImageVector
		get() {
			if (_style != null) {
				return _style!!
			}
			_style = materialIcon(name = "AppIcons.Style") {
				materialPath {
					moveTo(2.53f, 19.65f)
					lineToRelative(1.34f, 0.56f)
					verticalLineToRelative(-9.03f)
					lineToRelative(-2.43f, 5.86f)
					curveToRelative(-0.41f, 1.02f, 0.08f, 2.19f, 1.09f, 2.61f)
					close()
					moveTo(22.03f, 15.95f)
					lineTo(17.07f, 3.98f)
					curveToRelative(-0.31f, -0.75f, -1.04f, -1.21f, -1.81f, -1.23f)
					curveToRelative(-0.26f, 0.0f, -0.53f, 0.04f, -0.79f, 0.15f)
					lineTo(7.1f, 5.95f)
					curveToRelative(-0.75f, 0.31f, -1.21f, 1.03f, -1.23f, 1.8f)
					curveToRelative(-0.01f, 0.27f, 0.04f, 0.54f, 0.15f, 0.8f)
					lineToRelative(4.96f, 11.97f)
					curveToRelative(0.31f, 0.76f, 1.05f, 1.22f, 1.83f, 1.23f)
					curveToRelative(0.26f, 0.0f, 0.52f, -0.05f, 0.77f, -0.15f)
					lineToRelative(7.36f, -3.05f)
					curveToRelative(1.02f, -0.42f, 1.51f, -1.59f, 1.09f, -2.6f)
					close()
					moveTo(12.83f, 19.75f)
					lineTo(7.87f, 7.79f)
					lineToRelative(7.35f, -3.04f)
					horizontalLineToRelative(0.01f)
					lineToRelative(4.95f, 11.95f)
					lineToRelative(-7.35f, 3.05f)
					close()
				}
				materialPath {
					moveTo(11.0f, 9.0f)
					moveToRelative(-1.0f, 0.0f)
					arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
					arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
				}
				materialPath {
					moveTo(5.88f, 19.75f)
					curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
					horizontalLineToRelative(1.45f)
					lineToRelative(-3.45f, -8.34f)
					verticalLineToRelative(6.34f)
					close()
				}
			}
			return _style!!
		}

	private var _style: ImageVector? = null

	val TravelExplore: ImageVector
		get() {
			if (_travelExplore != null) {
				return _travelExplore!!
			}
			_travelExplore = materialIcon(name = "AppIcons.TravelExplore") {
				materialPath {
					moveTo(19.3f, 16.9f)
					curveToRelative(0.4f, -0.7f, 0.7f, -1.5f, 0.7f, -2.4f)
					curveToRelative(0.0f, -2.5f, -2.0f, -4.5f, -4.5f, -4.5f)
					reflectiveCurveTo(11.0f, 12.0f, 11.0f, 14.5f)
					reflectiveCurveToRelative(2.0f, 4.5f, 4.5f, 4.5f)
					curveToRelative(0.9f, 0.0f, 1.7f, -0.3f, 2.4f, -0.7f)
					lineToRelative(3.2f, 3.2f)
					lineToRelative(1.4f, -1.4f)
					lineTo(19.3f, 16.9f)
					close()
					moveTo(15.5f, 17.0f)
					curveToRelative(-1.4f, 0.0f, -2.5f, -1.1f, -2.5f, -2.5f)
					reflectiveCurveToRelative(1.1f, -2.5f, 2.5f, -2.5f)
					reflectiveCurveToRelative(2.5f, 1.1f, 2.5f, 2.5f)
					reflectiveCurveTo(16.9f, 17.0f, 15.5f, 17.0f)
					close()
					moveTo(12.0f, 20.0f)
					verticalLineToRelative(2.0f)
					curveTo(6.48f, 22.0f, 2.0f, 17.52f, 2.0f, 12.0f)
					curveTo(2.0f, 6.48f, 6.48f, 2.0f, 12.0f, 2.0f)
					curveToRelative(4.84f, 0.0f, 8.87f, 3.44f, 9.8f, 8.0f)
					horizontalLineToRelative(-2.07f)
					curveToRelative(-0.64f, -2.46f, -2.4f, -4.47f, -4.73f, -5.41f)
					verticalLineTo(5.0f)
					curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
					horizontalLineToRelative(-2.0f)
					verticalLineToRelative(2.0f)
					curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
					horizontalLineTo(8.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(2.0f)
					verticalLineToRelative(3.0f)
					horizontalLineTo(9.0f)
					lineToRelative(-4.79f, -4.79f)
					curveTo(4.08f, 10.79f, 4.0f, 11.38f, 4.0f, 12.0f)
					curveTo(4.0f, 16.41f, 7.59f, 20.0f, 12.0f, 20.0f)
					close()
				}
			}
			return _travelExplore!!
		}

	private var _travelExplore: ImageVector? = null

	val Tune: ImageVector
		get() {
			if (_tune != null) {
				return _tune!!
			}
			_tune = materialIcon(name = "AppIcons.Tune") {
				materialPath {
					moveTo(3.0f, 17.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(6.0f)
					verticalLineToRelative(-2.0f)
					lineTo(3.0f, 17.0f)
					close()
					moveTo(3.0f, 5.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(10.0f)
					lineTo(13.0f, 5.0f)
					lineTo(3.0f, 5.0f)
					close()
					moveTo(13.0f, 21.0f)
					verticalLineToRelative(-2.0f)
					horizontalLineToRelative(8.0f)
					verticalLineToRelative(-2.0f)
					horizontalLineToRelative(-8.0f)
					verticalLineToRelative(-2.0f)
					horizontalLineToRelative(-2.0f)
					verticalLineToRelative(6.0f)
					horizontalLineToRelative(2.0f)
					close()
					moveTo(7.0f, 9.0f)
					verticalLineToRelative(2.0f)
					lineTo(3.0f, 11.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(4.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(2.0f)
					lineTo(9.0f, 9.0f)
					lineTo(7.0f, 9.0f)
					close()
					moveTo(21.0f, 13.0f)
					verticalLineToRelative(-2.0f)
					lineTo(11.0f, 11.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(10.0f)
					close()
					moveTo(15.0f, 9.0f)
					horizontalLineToRelative(2.0f)
					lineTo(17.0f, 7.0f)
					horizontalLineToRelative(4.0f)
					lineTo(21.0f, 5.0f)
					horizontalLineToRelative(-4.0f)
					lineTo(17.0f, 3.0f)
					horizontalLineToRelative(-2.0f)
					verticalLineToRelative(6.0f)
					close()
				}
			}
			return _tune!!
		}

	private var _tune: ImageVector? = null

	val Visibility: ImageVector
		get() {
			if (_visibility != null) {
				return _visibility!!
			}
			_visibility = materialIcon(name = "AppIcons.Visibility") {
				materialPath {
					moveTo(12.0f, 6.0f)
					curveToRelative(3.79f, 0.0f, 7.17f, 2.13f, 8.82f, 5.5f)
					curveTo(19.17f, 14.87f, 15.79f, 17.0f, 12.0f, 17.0f)
					reflectiveCurveToRelative(-7.17f, -2.13f, -8.82f, -5.5f)
					curveTo(4.83f, 8.13f, 8.21f, 6.0f, 12.0f, 6.0f)
					moveToRelative(0.0f, -2.0f)
					curveTo(7.0f, 4.0f, 2.73f, 7.11f, 1.0f, 11.5f)
					curveTo(2.73f, 15.89f, 7.0f, 19.0f, 12.0f, 19.0f)
					reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
					curveTo(21.27f, 7.11f, 17.0f, 4.0f, 12.0f, 4.0f)
					close()
					moveTo(12.0f, 9.0f)
					curveToRelative(1.38f, 0.0f, 2.5f, 1.12f, 2.5f, 2.5f)
					reflectiveCurveTo(13.38f, 14.0f, 12.0f, 14.0f)
					reflectiveCurveToRelative(-2.5f, -1.12f, -2.5f, -2.5f)
					reflectiveCurveTo(10.62f, 9.0f, 12.0f, 9.0f)
					moveToRelative(0.0f, -2.0f)
					curveToRelative(-2.48f, 0.0f, -4.5f, 2.02f, -4.5f, 4.5f)
					reflectiveCurveTo(9.52f, 16.0f, 12.0f, 16.0f)
					reflectiveCurveToRelative(4.5f, -2.02f, 4.5f, -4.5f)
					reflectiveCurveTo(14.48f, 7.0f, 12.0f, 7.0f)
					close()
				}
			}
			return _visibility!!
		}

	private var _visibility: ImageVector? = null

	val VisibilityOff: ImageVector
		get() {
			if (_visibilityOff != null) {
				return _visibilityOff!!
			}
			_visibilityOff = materialIcon(name = "AppIcons.VisibilityOff") {
				materialPath {
					moveTo(12.0f, 6.0f)
					curveToRelative(3.79f, 0.0f, 7.17f, 2.13f, 8.82f, 5.5f)
					curveToRelative(-0.59f, 1.22f, -1.42f, 2.27f, -2.41f, 3.12f)
					lineToRelative(1.41f, 1.41f)
					curveToRelative(1.39f, -1.23f, 2.49f, -2.77f, 3.18f, -4.53f)
					curveTo(21.27f, 7.11f, 17.0f, 4.0f, 12.0f, 4.0f)
					curveToRelative(-1.27f, 0.0f, -2.49f, 0.2f, -3.64f, 0.57f)
					lineToRelative(1.65f, 1.65f)
					curveTo(10.66f, 6.09f, 11.32f, 6.0f, 12.0f, 6.0f)
					close()
					moveTo(10.93f, 7.14f)
					lineTo(13.0f, 9.21f)
					curveToRelative(0.57f, 0.25f, 1.03f, 0.71f, 1.28f, 1.28f)
					lineToRelative(2.07f, 2.07f)
					curveToRelative(0.08f, -0.34f, 0.14f, -0.7f, 0.14f, -1.07f)
					curveTo(16.5f, 9.01f, 14.48f, 7.0f, 12.0f, 7.0f)
					curveToRelative(-0.37f, 0.0f, -0.72f, 0.05f, -1.07f, 0.14f)
					close()
					moveTo(2.01f, 3.87f)
					lineToRelative(2.68f, 2.68f)
					curveTo(3.06f, 7.83f, 1.77f, 9.53f, 1.0f, 11.5f)
					curveTo(2.73f, 15.89f, 7.0f, 19.0f, 12.0f, 19.0f)
					curveToRelative(1.52f, 0.0f, 2.98f, -0.29f, 4.32f, -0.82f)
					lineToRelative(3.42f, 3.42f)
					lineToRelative(1.41f, -1.41f)
					lineTo(3.42f, 2.45f)
					lineTo(2.01f, 3.87f)
					close()
					moveTo(9.51f, 11.37f)
					lineToRelative(2.61f, 2.61f)
					curveToRelative(-0.04f, 0.01f, -0.08f, 0.02f, -0.12f, 0.02f)
					curveToRelative(-1.38f, 0.0f, -2.5f, -1.12f, -2.5f, -2.5f)
					curveToRelative(0.0f, -0.05f, 0.01f, -0.08f, 0.01f, -0.13f)
					close()
					moveTo(6.11f, 7.97f)
					lineToRelative(1.75f, 1.75f)
					curveToRelative(-0.23f, 0.55f, -0.36f, 1.15f, -0.36f, 1.78f)
					curveToRelative(0.0f, 2.48f, 2.02f, 4.5f, 4.5f, 4.5f)
					curveToRelative(0.63f, 0.0f, 1.23f, -0.13f, 1.77f, -0.36f)
					lineToRelative(0.98f, 0.98f)
					curveToRelative(-0.88f, 0.24f, -1.8f, 0.38f, -2.75f, 0.38f)
					curveToRelative(-3.79f, 0.0f, -7.17f, -2.13f, -8.82f, -5.5f)
					curveToRelative(0.7f, -1.43f, 1.72f, -2.61f, 2.93f, -3.53f)
					close()
				}
			}
			return _visibilityOff!!
		}

	private var _visibilityOff: ImageVector? = null

	val OpenInNew: ImageVector
		get() {
			if (_openInNew != null) {
				return _openInNew!!
			}
			_openInNew = materialIcon(name = "AppIcons.OpenInNew", autoMirror = true) {
				materialPath {
					moveTo(19.0f, 19.0f)
					horizontalLineTo(5.0f)
					verticalLineTo(5.0f)
					horizontalLineToRelative(7.0f)
					verticalLineTo(3.0f)
					horizontalLineTo(5.0f)
					curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
					verticalLineToRelative(14.0f)
					curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
					horizontalLineToRelative(14.0f)
					curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
					verticalLineToRelative(-7.0f)
					horizontalLineToRelative(-2.0f)
					verticalLineToRelative(7.0f)
					close()
					moveTo(14.0f, 3.0f)
					verticalLineToRelative(2.0f)
					horizontalLineToRelative(3.59f)
					lineToRelative(-9.83f, 9.83f)
					lineToRelative(1.41f, 1.41f)
					lineTo(19.0f, 6.41f)
					verticalLineTo(10.0f)
					horizontalLineToRelative(2.0f)
					verticalLineTo(3.0f)
					horizontalLineToRelative(-7.0f)
					close()
				}
			}
			return _openInNew!!
		}

	private var _openInNew: ImageVector? = null

}
