package fs2.internal

private[fs2] final case object AcquireAfterScopeClosed extends Throwable {
  override def fillInStackTrace = this
}
