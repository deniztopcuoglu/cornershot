package io.github.deniztopcuoglu.cornershot

internal object InternalActions {
    const val HIDE_OVERLAY = "io.github.deniztopcuoglu.cornershot.action.HIDE_OVERLAY"
    const val SHOW_OVERLAY = "io.github.deniztopcuoglu.cornershot.action.SHOW_OVERLAY"
    const val CAPTURE_FINISHED = "io.github.deniztopcuoglu.cornershot.action.CAPTURE_FINISHED"
    const val EXTRA_SOURCE_URI = "source_uri"

    fun intent(context: android.content.Context, action: String) =
        android.content.Intent(action).setPackage(context.packageName)
}
