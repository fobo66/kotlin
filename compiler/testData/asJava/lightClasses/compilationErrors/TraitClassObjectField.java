<<<<<<< HEAD
public interface TraitClassObjectField {
    TraitClassObjectField.Companion Companion;
    @org.jetbrains.annotations.Nullable
    java.lang.String x = "";

    static final class Companion {
        @org.jetbrains.annotations.Nullable
        public static final java.lang.String x = "";
        private static final java.lang.String y;

        private Companion() { /* compiled code */ }
    }
}
=======
public abstract interface TraitClassObjectField /* TraitClassObjectField*/ {
  @org.jetbrains.annotations.NotNull()
  public static final TraitClassObjectField.Companion Companion;

  @org.jetbrains.annotations.Nullable()
  public static final java.lang.String x = "" /* initializer type: java.lang.String */ /* constant value  */;


public static final class Companion /* TraitClassObjectField.Companion*/ {
  @org.jetbrains.annotations.Nullable()
  public static final java.lang.String x = "" /* initializer type: java.lang.String */ /* constant value  */;

  private static final java.lang.String y;

  private  Companion();//  .ctor()

}}
>>>>>>> 309bf49a832... Update lightclass tests renderer
