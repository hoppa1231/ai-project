package app.chspch.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Burgundy=Color(0xFF7A1313)
val Gold=Color(0xFFD9B450)
val Bone=Color(0xFFF0E6CC)
val Paper=Color(0xFFECE1C2)
val Playfair=FontFamily(Font("fonts/playfair_display_regular.ttf"),Font("fonts/playfair_display_bold_italic.ttf",FontWeight.Bold,FontStyle.Italic))
val PtSans=FontFamily(Font("fonts/pt_sans_regular.ttf"),Font("fonts/pt_sans_bold.ttf",FontWeight.Bold))
val Russo=FontFamily(Font("fonts/russo_one_regular.ttf"))
val PosterTypography=Typography(
    bodyLarge=androidx.compose.ui.text.TextStyle(fontFamily=PtSans,fontSize=16.sp),
    bodyMedium=androidx.compose.ui.text.TextStyle(fontFamily=PtSans,fontSize=14.sp),
    bodySmall=androidx.compose.ui.text.TextStyle(fontFamily=PtSans,fontSize=12.sp),
    labelLarge=androidx.compose.ui.text.TextStyle(fontFamily=PtSans,fontWeight=FontWeight.Bold,fontSize=14.sp),
    titleMedium=androidx.compose.ui.text.TextStyle(fontFamily=Russo,fontSize=17.sp),
    headlineMedium=androidx.compose.ui.text.TextStyle(fontFamily=Playfair,fontWeight=FontWeight.Bold,fontStyle=FontStyle.Italic,fontSize=30.sp)
)
@Composable fun PosterTheme(night:Boolean,content:@Composable ()->Unit){
    val colors=if(night) darkColorScheme(primary=Gold,onPrimary=Color(0xFF2A0606),background=Color(0xFF210C09),surface=Color(0xFF34120D),onSurface=Bone,onBackground=Bone,secondary=Gold,outline=Gold.copy(alpha=.4f),surfaceVariant=Color(0xFF461710),onSurfaceVariant=Bone.copy(alpha=.8f))
    else lightColorScheme(primary=Burgundy,onPrimary=Bone,background=Paper,surface=Color(0xFFF7EED8),onSurface=Color(0xFF392216),onBackground=Color(0xFF392216),secondary=Burgundy,outline=Burgundy.copy(alpha=.4f),surfaceVariant=Color(0xFFE4D5AF),onSurfaceVariant=Color(0xFF5A4030))
    MaterialTheme(colorScheme=colors,typography=PosterTypography,shapes=Shapes(small=RoundedCornerShape(3.dp),medium=RoundedCornerShape(4.dp),large=RoundedCornerShape(4.dp)),content=content)
}
fun Modifier.posterTexture(night:Boolean)=drawWithCache{
    val grain=if(night) Bone.copy(alpha=.045f) else Burgundy.copy(alpha=.045f)
    val glow=Brush.radialGradient(listOf(Gold.copy(alpha=if(night).12f else .04f),Color.Transparent),center=Offset(size.width*.4f,0f),radius=size.maxDimension)
    onDrawBehind{drawRect(glow);var x=0f;while(x<size.width){var y=0f;while(y<size.height){val seed=((x.toInt()*31+y.toInt()*17)%9);drawCircle(grain,.5f+seed*.08f,Offset(x+seed,y));y+=13f};x+=13f}}
}
@Composable fun PosterPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Column(modifier.border(1.dp,MaterialTheme.colorScheme.outline).background(MaterialTheme.colorScheme.surface.copy(alpha=.72f)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}
@Composable fun Ornament(modifier:Modifier=Modifier){
    Row(modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outline);Text("✦",color=MaterialTheme.colorScheme.primary);HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outline)
    }
}
